package org.ib.ibbackend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.ib.ibbackend.config.MailMfaProperties;
import org.ib.ibbackend.dto.ContactMessageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

@Service
public class ContactMessageService {
    private static final String DEFAULT_SUPPORT_EMAIL = "gurushopops@outlook.com";
    private static final String SUBJECT = "Contact Us message";

    private final JavaMailSender mailSender;
    private final BrevoEmailService brevoEmailService;
    private final MailMfaProperties mailProperties;
    private final String supportEmail;
    private final String mailProvider;
    private final String smtpPassword;

    public ContactMessageService(JavaMailSender mailSender,
                                BrevoEmailService brevoEmailService,
                                MailMfaProperties mailProperties,
                                @Value("${app.contact.support-email:" + DEFAULT_SUPPORT_EMAIL + "}") String supportEmail,
                                @Value("${app.mail.provider:auto}") String mailProvider,
                                @Value("${spring.mail.password:}") String smtpPassword) {
        this.mailSender = mailSender;
        this.brevoEmailService = brevoEmailService;
        this.mailProperties = mailProperties;
        this.supportEmail = supportEmail;
        this.mailProvider = mailProvider == null ? "auto" : mailProvider.trim();
        this.smtpPassword = smtpPassword == null ? "" : smtpPassword.trim();
    }

    public void send(ContactMessageRequest request) {
        String content = formatMessage(request);
        if (usesBrevoApi()) {
            if (brevoEmailService == null || !brevoEmailService.isConfigured()) {
                throw unavailable();
            }
            try {
                brevoEmailService.send(
                        mailProperties.getFrom(),
                        supportEmail,
                        SUBJECT,
                        content,
                        request.getEmail()
                );
            } catch (BrevoEmailService.BrevoDeliveryException ex) {
                throw deliveryFailed(ex);
            }
            return;
        }

        if (!"smtp".equalsIgnoreCase(mailProvider) && !"auto".equalsIgnoreCase(mailProvider)) {
            throw unavailable();
        }
        if (smtpPassword.isBlank()) {
            throw unavailable();
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(mailProperties.getFrom());
            helper.setTo(supportEmail);
            helper.setReplyTo(request.getEmail().trim());
            helper.setSubject(SUBJECT);
            helper.setText(content, false);
            mailSender.send(message);
        } catch (MessagingException | MailException ex) {
            throw deliveryFailed(ex);
        }
    }

    private boolean usesBrevoApi() {
        if ("brevo-api".equalsIgnoreCase(mailProvider)) {
            return true;
        }
        if ("smtp".equalsIgnoreCase(mailProvider)) {
            return false;
        }
        if (!"auto".equalsIgnoreCase(mailProvider)) {
            throw unavailable();
        }
        return brevoEmailService != null && brevoEmailService.isConfigured();
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Contact messages are temporarily unavailable. Please email support directly."
        );
    }

    private ResponseStatusException deliveryFailed(Exception cause) {
        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "We could not send your message. Please try again or email support directly.",
                cause
        );
    }

    private String formatMessage(ContactMessageRequest request) {
        String phone = request.getPhone() == null || request.getPhone().isBlank()
                ? "Not provided"
                : request.getPhone().trim();
        return "Name: " + request.getName().trim()
                + "\nEmail: " + request.getEmail().trim()
                + "\nPhone: " + phone
                + "\n\nMessage:\n" + request.getMessage().trim();
    }
}
