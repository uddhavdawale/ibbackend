package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.RefreshToken;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private static final Duration REPLAY_GRACE_PERIOD = Duration.ofSeconds(30);

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtService jwtService) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
    }

    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    @Transactional
    public RefreshToken storeToken(User user, String refreshToken) {
        if (user == null || user.getId() == null || refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("User and refresh token are required");
        }

        String tokenHash = hashToken(refreshToken);
        Optional<RefreshToken> existingToken = refreshTokenRepository.findByTokenHash(tokenHash);
        if (existingToken.isPresent()) {
            return existingToken.get();
        }

        return createToken(user, refreshToken, UUID.randomUUID().toString());
    }

    private RefreshToken createToken(User user, String refreshToken, String familyId) {
        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(hashToken(refreshToken));
        entity.setFamilyId(familyId);
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setExpiresAt(
                jwtService.extractRefreshClaims(refreshToken)
                        .getExpiration()
                        .toInstant()
                        .atZone(ZoneOffset.UTC)
                        .toLocalDateTime()
        );
        return refreshTokenRepository.save(entity);
    }

    public boolean isValidForUser(User user, String refreshToken) {
        if (user == null || user.getId() == null || refreshToken == null || refreshToken.isBlank()) {
            return false;
        }

        try {
            if (!jwtService.isRefreshTokenValid(refreshToken, user)) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        String tokenHash = hashToken(refreshToken);
        return refreshTokenRepository.findByTokenHash(tokenHash)
                .map(token -> token.getUser() != null
                        && user.getId().equals(token.getUser().getId())
                        && !token.isRevoked()
                        && token.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC)))
                .orElse(false);
    }

    @Transactional
    public void revokeToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        refreshTokenRepository.findByTokenHash(hashToken(refreshToken))
                .ifPresent(token -> {
                    token.setRevoked(true);
                    token.setRevokedAt(LocalDateTime.now());
                    refreshTokenRepository.save(token);
                });
    }

    @Transactional
    public Optional<String> rotateToken(User user, String oldRefreshToken, String newRefreshToken) {
        if (user == null || user.getId() == null || oldRefreshToken == null || oldRefreshToken.isBlank()) {
            return Optional.empty();
        }

        if (!jwtService.isRefreshTokenValid(oldRefreshToken, user)
                || !jwtService.isRefreshTokenValid(newRefreshToken, user)) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        RefreshToken currentToken = refreshTokenRepository.findByTokenHashForUpdate(hashToken(oldRefreshToken))
                .orElse(null);
        if (currentToken == null || currentToken.getUser() == null
                || !user.getId().equals(currentToken.getUser().getId())) {
            return Optional.empty();
        }

        String familyId = currentToken.getFamilyId() == null
                ? currentToken.getTokenHash()
                : currentToken.getFamilyId();
        if (currentToken.isRevoked()) {
            if (currentToken.getReplacedByTokenHash() != null
                    && currentToken.getReplacementGraceUntil() != null
                    && now.isBefore(currentToken.getReplacementGraceUntil())
                    && currentToken.getReplacementTokenCiphertext() != null) {
                String replacement = jwtService.decryptRefreshTokenForReplay(
                        currentToken.getReplacementTokenCiphertext());
                RefreshToken replacementRecord = refreshTokenRepository
                        .findByTokenHashForUpdate(currentToken.getReplacedByTokenHash())
                        .orElse(null);
                if (hashToken(replacement).equals(currentToken.getReplacedByTokenHash())
                        && replacementRecord != null
                        && !replacementRecord.isRevoked()
                        && familyId.equals(replacementRecord.getFamilyId())
                        && replacementRecord.getExpiresAt().isAfter(now)
                        && jwtService.isRefreshTokenValid(replacement, user)) {
                    return Optional.of(replacement);
                }
            }
            refreshTokenRepository.revokeFamily(familyId, now);
            return Optional.empty();
        }

        if (currentToken.getExpiresAt() == null || !currentToken.getExpiresAt().isAfter(now)) {
            refreshTokenRepository.revokeFamily(familyId, now);
            return Optional.empty();
        }

        currentToken.setRevoked(true);
        currentToken.setRevokedAt(now);
        currentToken.setReplacedByTokenHash(hashToken(newRefreshToken));
        currentToken.setReplacementTokenCiphertext(jwtService.encryptRefreshTokenForReplay(newRefreshToken));
        currentToken.setReplacementGraceUntil(now.plus(REPLAY_GRACE_PERIOD));
        refreshTokenRepository.save(currentToken);

        createToken(user, newRefreshToken, familyId);
        return Optional.of(newRefreshToken);
    }

    @Transactional
    public void revokeAllActiveTokensForUser(User user) {
        if (user == null || user.getId() == null) {
            return;
        }

        refreshTokenRepository.revokeAllActiveForUser(user.getId(), LocalDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public void revokeFamilyForToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hashToken(refreshToken)).ifPresent(token -> {
            String familyId = token.getFamilyId() == null ? token.getTokenHash() : token.getFamilyId();
            refreshTokenRepository.revokeFamily(familyId, LocalDateTime.now(ZoneOffset.UTC));
        });
    }

    public boolean hasActiveTokenForUser(User user) {
        if (user == null || user.getId() == null) {
            return false;
        }

        return refreshTokenRepository.existsByUserIdAndRevokedFalse(user.getId());
    }
}
