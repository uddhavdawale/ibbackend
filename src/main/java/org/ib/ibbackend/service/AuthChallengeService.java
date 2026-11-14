package org.ib.ibbackend.service;

import org.ib.ibbackend.config.AuthFlowProperties;
import org.ib.ibbackend.config.MailMfaProperties;
import org.ib.ibbackend.entity.AuthChallenge;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.AuthChallengeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthChallengeService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_SENDS = 3;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int RESEND_COOLDOWN_SECONDS = 30;

    private final AuthChallengeRepository challengeRepository;
    private final AuthFlowProperties properties;
    private final MailMfaProperties mailProperties;
    private final byte[] otpHmacKey;

    public AuthChallengeService(AuthChallengeRepository challengeRepository,
                                AuthFlowProperties properties,
                                MailMfaProperties mailProperties) {
        this.challengeRepository = challengeRepository;
        this.properties = properties;
        this.mailProperties = mailProperties;
        String configuredKey = properties.getMfaOtpHmacSecret();
        this.otpHmacKey = deriveKey(configuredKey);
    }

    private byte[] deriveKey(String configuredKey) {
        if (configuredKey == null || configuredKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            return new byte[0];
        }
        try {
            Mac keyDerivation = Mac.getInstance("HmacSHA256");
            keyDerivation.init(new SecretKeySpec(configuredKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return keyDerivation.doFinal(
                    "ibbackend:mail-mfa-otp:v1".getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            throw new IllegalStateException("Unable to derive MFA digest key", ex);
        }
    }

    @Transactional
    public IssuedChallenge createChallenge(User user) {
        requireHmacKey();
        if (user == null || user.getId() == null || user.isDisabled()) {
            throw new IllegalArgumentException("An active account is required for an MFA challenge");
        }
        LocalDateTime now = utcNow();
        String challengeId = UUID.randomUUID().toString();
        byte[] cookieBytes = new byte[32];
        RANDOM.nextBytes(cookieBytes);
        String cookieValue = Base64.getUrlEncoder().withoutPadding().encodeToString(cookieBytes);

        AuthChallenge challenge = new AuthChallenge();
        challenge.setId(challengeId);
        challenge.setUser(user);
        challenge.setPreAuthCookieDigest(TokenDigest.sha256(cookieValue));
        challenge.setCreatedAt(now);
        challenge.setExpiresAt(now.plusMinutes(clamp(properties.getMfaChallengeExpiryMinutes(), 5, 15)));
        challengeRepository.save(challenge);

        return new IssuedChallenge(challengeId, cookieValue, maskEmail(user.getEmail()), challenge.getExpiresAt());
    }

    @Transactional
    public PendingOtp prepareOtp(String challengeId, String preAuthCookie) {
        AuthChallenge challenge = requireBoundChallenge(challengeId, preAuthCookie);
        LocalDateTime now = utcNow();
        if (challenge.getSendCount() >= MAX_SENDS) {
            throw new ResendLimitException("MFA code send limit reached");
        }
        if (challenge.getLastSentAt() != null
                && challenge.getLastSentAt().plusSeconds(RESEND_COOLDOWN_SECONDS).isAfter(now)) {
            throw new ResendLimitException("Please wait before requesting another MFA code");
        }

        String otp = generateOtp();
        challenge.setOtpDigest(hmac(challenge.getId() + ":" + otp));
        challenge.setOtpExpiresAt(now.plusMinutes(clamp(mailProperties.getExpiryMinutes(), 1, 5)));
        challenge.setLastSentAt(now);
        challenge.setSendCount(challenge.getSendCount() + 1);
        challenge.setFailedAttempts(0);
        challengeRepository.save(challenge);
        return new PendingOtp(challenge.getUser().getEmail(), otp, maskEmail(challenge.getUser().getEmail()));
    }

    @Transactional
    public Optional<User> verifyOtp(String challengeId, String preAuthCookie, String otp) {
        AuthChallenge challenge;
        try {
            challenge = requireBoundChallenge(challengeId, preAuthCookie);
        } catch (ChallengeLimitException ex) {
            return Optional.empty();
        }

        LocalDateTime now = utcNow();
        if (challenge.getOtpDigest() == null
                || challenge.getOtpExpiresAt() == null
                || !challenge.getOtpExpiresAt().isAfter(now)
                || challenge.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
            return Optional.empty();
        }

        String candidate = otp != null && otp.matches("\\d{6}")
                ? hmac(challenge.getId() + ":" + otp)
                : hmac(challenge.getId() + ":invalid");
        boolean valid = MessageDigest.isEqual(
                challenge.getOtpDigest().getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII)
        );
        if (!valid) {
            challenge.setFailedAttempts(challenge.getFailedAttempts() + 1);
            challengeRepository.save(challenge);
            return Optional.empty();
        }

        challenge.setConsumedAt(now);
        challenge.setOtpDigest(null);
        challenge.setOtpExpiresAt(null);
        challengeRepository.save(challenge);
        return Optional.of(challenge.getUser());
    }

    @Transactional
    public void invalidateOtp(String challengeId, String preAuthCookie) {
        try {
            AuthChallenge challenge = requireBoundChallenge(challengeId, preAuthCookie);
            challenge.setOtpDigest(null);
            challenge.setOtpExpiresAt(null);
            challengeRepository.save(challenge);
        } catch (ChallengeLimitException ex) {
            // The challenge may have expired between enqueue and invalidation.
        }
    }

    @Transactional
    public void cancelChallenge(String challengeId, String preAuthCookie) {
        AuthChallenge challenge = requireBoundChallenge(challengeId, preAuthCookie);
        challenge.setConsumedAt(utcNow());
        challenge.setOtpDigest(null);
        challenge.setOtpExpiresAt(null);
        challengeRepository.save(challenge);
    }

    private AuthChallenge requireBoundChallenge(String challengeId, String preAuthCookie) {
        if (challengeId == null || challengeId.isBlank()
                || preAuthCookie == null || preAuthCookie.isBlank()) {
            throw new ChallengeLimitException("MFA challenge is invalid");
        }
        AuthChallenge challenge = challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new ChallengeLimitException("MFA challenge is invalid"));
        byte[] stored = challenge.getPreAuthCookieDigest().getBytes(StandardCharsets.US_ASCII);
        byte[] candidate = TokenDigest.sha256(preAuthCookie).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(stored, candidate)
                || challenge.getConsumedAt() != null
                || !challenge.getExpiresAt().isAfter(utcNow())
                || challenge.getUser().isDisabled()) {
            throw new ChallengeLimitException("MFA challenge is invalid");
        }
        return challenge;
    }

    private String generateOtp() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format("%06d", value);
    }

    private String hmac(String value) {
        requireHmacKey();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(otpHmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            throw new IllegalStateException("Unable to calculate MFA code digest", ex);
        }
    }

    private void requireHmacKey() {
        if (otpHmacKey.length != 32) {
            throw new IllegalStateException("APP_MFA_OTP_HMAC_SECRET must contain at least 32 bytes");
        }
    }

    private String maskEmail(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        return local.substring(0, 1) + "***@" + email.substring(at + 1);
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record IssuedChallenge(String challengeId, String cookieValue, String maskedDestination,
                                  LocalDateTime expiresAt) {
    }

    public record PendingOtp(String email, String otp, String maskedDestination) {
    }

    public static class ChallengeLimitException extends RuntimeException {
        public ChallengeLimitException(String message) {
            super(message);
        }
    }

    public static final class ResendLimitException extends ChallengeLimitException {
        public ResendLimitException(String message) {
            super(message);
        }
    }
}
