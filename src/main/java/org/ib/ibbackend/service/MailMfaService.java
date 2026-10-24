package org.ib.ibbackend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.ib.ibbackend.config.MailMfaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MailMfaService {
    private static final Logger log = LoggerFactory.getLogger(MailMfaService.class);

    private final MailMfaProperties properties;
    private final JavaMailSender mailSender;
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();

    public MailMfaService(MailMfaProperties properties, JavaMailSender mailSender) {
        this.properties = properties;
        this.mailSender = mailSender;
    }

    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    public String issueOtp(String email) {
        String normalizedEmail = normalizeEmail(email);
        String otp = generateOtp();
        otpStore.put(normalizedEmail, new OtpEntry(otp, Instant.now().plus(properties.getExpiryMinutes(), ChronoUnit.MINUTES)));

        if (isEnabled() && mailSender != null) {
            sendMail(normalizedEmail, otp);
        } else {
            log.info("Mail MFA is disabled or mail sender is not configured. Generated OTP for {}: {}", normalizedEmail, otp);
        }

        return otp;
    }

    public boolean verifyOtp(String email, String otp) {
        if (email == null || otp == null) {
            return false;
        }

        OtpEntry stored = otpStore.get(normalizeEmail(email));
        if (stored == null) {
            return false;
        }

        boolean valid = stored.otp.equals(otp) && Instant.now().isBefore(stored.expiresAt);
        if (valid) {
            otpStore.remove(normalizeEmail(email));
        }
        return valid;
    }

    private String generateOtp() {
        int length = properties.getOtpLength();
        Random random = new Random();
        StringBuilder otp = new StringBuilder();
        for (int i = 0; i < length; i++) {
            otp.append(random.nextInt(10));
        }
        return otp.toString();
    }

    private void sendMail(String email, String otp) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(properties.getFrom());
            helper.setTo(email);
            helper.setSubject(properties.getSubject());
            helper.setText("Your verification code is " + otp + ". It expires in " + properties.getExpiryMinutes() + " minutes.");
            mailSender.send(message);
            log.info("MFA OTP sent to {}", email);
        } catch (MessagingException e) {
            log.error("Failed to send MFA OTP email to {}", email, e);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static class OtpEntry {
        private final String otp;
        private final Instant expiresAt;

        private OtpEntry(String otp, Instant expiresAt) {
            this.otp = otp;
            this.expiresAt = expiresAt;
        }
    }
}
