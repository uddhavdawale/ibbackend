package org.ib.ibbackend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.ib.ibbackend.config.MailMfaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MailMfaService {
    private static final Logger log = LoggerFactory.getLogger(MailMfaService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
            log.info("Mail MFA is disabled or mail sender is not configured. OTP generated for {}", normalizedEmail);
        }

        return otp;
    }

    public boolean verifyOtp(String email, String otp) {
        if (email == null || otp == null) {
            return false;
        }

        String normalizedEmail = normalizeEmail(email);
        OtpEntry stored = otpStore.get(normalizedEmail);
        if (stored == null) {
            return false;
        }

        if (Instant.now().isAfter(stored.expiresAt) || Instant.now().equals(stored.expiresAt)) {
            otpStore.remove(normalizedEmail);
            return false;
        }

        byte[] storedBytes = stored.otp.getBytes(StandardCharsets.UTF_8);
        byte[] candidateBytes = otp.getBytes(StandardCharsets.UTF_8);
        boolean valid = MessageDigest.isEqual(storedBytes, candidateBytes);
        if (valid) {
            otpStore.remove(normalizedEmail);
        }
        return valid;
    }

    private String generateOtp() {
        int length = properties.getOtpLength();
        StringBuilder otp = new StringBuilder();
        for (int i = 0; i < length; i++) {
            otp.append(SECURE_RANDOM.nextInt(10));
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
