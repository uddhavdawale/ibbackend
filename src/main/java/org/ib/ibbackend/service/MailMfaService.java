package org.ib.ibbackend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.ib.ibbackend.config.AuthFlowProperties;
import org.ib.ibbackend.config.MailMfaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.MailException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Service
public class MailMfaService {
    private static final Logger log = LoggerFactory.getLogger(MailMfaService.class);

    private final MailMfaProperties properties;
    private final AuthFlowProperties authFlowProperties;
    private final JavaMailSender mailSender;
    private final ExecutorService mailDeliveryExecutor;
    private BrevoEmailService brevoEmailService;

    @Value("${app.mail.provider:auto}")
    private String mailProvider;

    @Value("${spring.mail.password:}")
    private String smtpPassword;

    public MailMfaService(MailMfaProperties properties,
                          AuthFlowProperties authFlowProperties,
                          JavaMailSender mailSender,
                          ExecutorService mailDeliveryExecutor) {
        this.properties = properties;
        this.authFlowProperties = authFlowProperties;
        this.mailSender = mailSender;
        this.mailDeliveryExecutor = mailDeliveryExecutor;
    }

    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    public boolean isReady() {
        if (!isEnabled()) {
            return false;
        }
        if ("brevo-api".equalsIgnoreCase(mailProvider)) {
            return isBrevoApiReady();
        }
        if ("smtp".equalsIgnoreCase(mailProvider)) {
            return isSmtpReady();
        }
        return isBrevoApiReady() || isSmtpReady();
    }

    public String getUnavailableReason() {
        if (!isEnabled()) {
            return "APP_MFA_MAIL_ENABLED is false";
        }
        if ("brevo-api".equalsIgnoreCase(mailProvider)) {
            return isBrevoApiReady() ? null : "BREVO_API_KEY is missing";
        }
        if ("smtp".equalsIgnoreCase(mailProvider)) {
            if (smtpPassword == null || smtpPassword.isBlank()) {
                return "SMTP_PASSWORD is missing";
            }
            return null;
        }
        if (isBrevoApiReady()) {
            return null;
        }
        if (smtpPassword == null || smtpPassword.isBlank()) {
            return "Neither BREVO_API_KEY nor SMTP_PASSWORD is configured";
        }
        return null;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBrevoEmailService(BrevoEmailService brevoEmailService) {
        this.brevoEmailService = brevoEmailService;
    }

    public void sendOtp(String email, String otp) {
        enqueue("MFA", () -> sendMessage(
                email,
                properties.getSubject(),
                "Your verification code is " + otp + ". It expires in "
                        + properties.getExpiryMinutes() + " minutes."
        ));
    }

    public void sendPasswordReset(String email, String token) {
        String resetUrl = buildPasswordResetUrl(token);
        enqueue("password reset", () -> sendMessage(
                email,
                "Password reset instructions",
                "Use the following link to reset your password. This link expires in "
                        + authFlowProperties.getPasswordResetExpiryMinutes() + " minutes:\n\n"
                        + resetUrl
        ));
    }

    String buildPasswordResetUrl(String token) {
        String baseUrl = authFlowProperties.getFrontendBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Password reset frontend URL is not configured");
        }
        return baseUrl.replaceAll("/+$", "")
                + "/reset-password#resetToken="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    public void sendPasswordChangedNotification(String email) {
        enqueue("password change notification", () -> sendMessage(
                email,
                "Your password was changed",
                "The password for your account was changed. If you did not make this change, contact support."
        ));
    }

    private void enqueue(String messageType, Runnable delivery) {
        if (!isReady()) {
            throw new IllegalStateException("Transactional email is not configured");
        }
        try {
            mailDeliveryExecutor.execute(() -> {
                try {
                    delivery.run();
                    log.debug("Mail delivery succeeded for message type {}", messageType);
                } catch (BrevoEmailService.BrevoDeliveryException ex) {
                    if (ex.getStatusCode() == null) {
                        log.error("Brevo API delivery failed for message type {} (connection error)",
                                messageType, ex.getCause());
                    } else {
                        ex.printStackTrace();
                        log.error("Brevo API delivery failed for message type {} (HTTP {})",
                                messageType, ex.getStatusCode());
                    }
                } catch (MailDeliveryException | MailException | IllegalArgumentException | IllegalStateException ex) {
                    log.error("Mail delivery failed for message type {} ({})",
                            messageType, ex.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException ex) {
            log.warn("Mail delivery queue is full for message type {}", messageType);
            throw new MailQueueFullException();
        }
    }

    private void sendMessage(String email, String subject, String body) {
        if (useBrevoApi()) {
            if (brevoEmailService == null || !brevoEmailService.isConfigured()) {
                throw new IllegalStateException("Brevo API key is not configured");
            }
            brevoEmailService.send(properties.getFrom(), email, subject, body);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getFrom());
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (MessagingException ex) {
            throw new MailDeliveryException(ex);
        }
    }

    private boolean useBrevoApi() {
        return "brevo-api".equalsIgnoreCase(mailProvider)
                || (!"smtp".equalsIgnoreCase(mailProvider) && isBrevoApiReady());
    }

    private boolean isBrevoApiReady() {
        return brevoEmailService != null && brevoEmailService.isConfigured();
    }

    private boolean isSmtpReady() {
        return mailSender != null && smtpPassword != null && !smtpPassword.isBlank();
    }

    public static final class MailQueueFullException extends RuntimeException {
        public MailQueueFullException() {
            super("Mail delivery queue is full");
        }
    }

    private static final class MailDeliveryException extends RuntimeException {
        private MailDeliveryException(MessagingException cause) {
            super(cause);
        }
    }
}
