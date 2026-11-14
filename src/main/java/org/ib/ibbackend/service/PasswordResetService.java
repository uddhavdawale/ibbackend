package org.ib.ibbackend.service;

import org.ib.ibbackend.config.AuthFlowProperties;
import org.ib.ibbackend.entity.PasswordResetToken;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.PasswordResetTokenRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

@Service
public class PasswordResetService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REQUEST_COOLDOWN_SECONDS = 60;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordSecurityService passwordSecurityService;
    private final RefreshTokenService refreshTokenService;
    private final AuthFlowProperties properties;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                UserRepository userRepository,
                                PasswordSecurityService passwordSecurityService,
                                RefreshTokenService refreshTokenService,
                                AuthFlowProperties properties) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.passwordSecurityService = passwordSecurityService;
        this.refreshTokenService = refreshTokenService;
        this.properties = properties;
    }

    @Transactional
    public Optional<ResetEmail> requestReset(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        Optional<User> userResult = userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT));
        if (userResult.isEmpty() || userResult.get().isDisabled()) {
            return Optional.empty();
        }
        User user = userRepository.findByEmailForUpdate(email.trim().toLowerCase(Locale.ROOT))
                .orElse(null);
        if (user == null || user.isDisabled()) {
            return Optional.empty();
        }
        LocalDateTime now = utcNow();
        if (tokenRepository.wasRecentlyIssued(user.getId(), now.minusSeconds(REQUEST_COOLDOWN_SECONDS))) {
            return Optional.empty();
        }

        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        tokenRepository.consumeOutstandingForUser(user.getId(), now);
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenDigest(TokenDigest.sha256(rawToken));
        token.setIssuedAt(now);
        token.setExpiresAt(now.plusMinutes(clamp(properties.getPasswordResetExpiryMinutes(), 5, 60)));
        tokenRepository.save(token);
        return Optional.of(new ResetEmail(user.getEmail(), rawToken));
    }

    @Transactional
    public Optional<User> resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256) {
            return Optional.empty();
        }
        if (newPassword != null && newPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at most " + MAX_PASSWORD_LENGTH + " characters long");
        }
        passwordSecurityService.validateNewPassword(newPassword);

        Optional<PasswordResetToken> result = tokenRepository.findByDigestForUpdate(TokenDigest.sha256(rawToken));
        if (result.isEmpty()) {
            return Optional.empty();
        }
        PasswordResetToken token = result.get();
        LocalDateTime now = utcNow();
        if (token.getConsumedAt() != null || !token.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }

        User user = token.getUser();
        if (user.isDisabled()) {
            token.setConsumedAt(now);
            return Optional.empty();
        }
        token.setConsumedAt(now);
        user.setPasswordHash(passwordSecurityService.hashNewPassword(newPassword));
        user.setAuthVersion(user.getAuthVersion() + 1);
        userRepository.save(user);
        refreshTokenService.revokeAllActiveTokensForUser(user);
        return Optional.of(user);
    }

    @Transactional
    public void invalidateResetToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokenRepository.findByDigestForUpdate(TokenDigest.sha256(rawToken)).ifPresent(token -> {
            token.setConsumedAt(utcNow());
            tokenRepository.save(token);
        });
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public record ResetEmail(String email, String token) {
    }
}
