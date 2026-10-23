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
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class RefreshTokenService {

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

        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(tokenHash);
        entity.setExpiresAt(
                jwtService.extractRefreshClaims(refreshToken)
                        .getExpiration()
                        .toInstant()
                        .atZone(ZoneId.systemDefault())
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
                        && !token.getExpiresAt().isBefore(LocalDateTime.now()))
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
    public void rotateToken(User user, String oldRefreshToken, String newRefreshToken) {
        if (user == null || user.getId() == null || oldRefreshToken == null || oldRefreshToken.isBlank()) {
            throw new IllegalArgumentException("Invalid refresh token rotation request");
        }

        RefreshToken currentToken = refreshTokenRepository.findByTokenHash(hashToken(oldRefreshToken))
                .orElseThrow(() -> new IllegalArgumentException("Refresh token not found"));

        if (!user.getId().equals(currentToken.getUser().getId()) || currentToken.isRevoked()) {
            throw new IllegalArgumentException("Refresh token is invalid or revoked");
        }

        currentToken.setRevoked(true);
        currentToken.setRevokedAt(LocalDateTime.now());
        currentToken.setReplacedByTokenHash(hashToken(newRefreshToken));
        refreshTokenRepository.save(currentToken);

        storeToken(user, newRefreshToken);
    }

    @Transactional
    public void revokeAllActiveTokensForUser(User user) {
        if (user == null || user.getId() == null) {
            return;
        }

        List<RefreshToken> activeTokens = refreshTokenRepository.findByUserIdAndRevokedFalse(user.getId());
        for (RefreshToken token : activeTokens) {
            token.setRevoked(true);
            token.setRevokedAt(LocalDateTime.now());
        }
        refreshTokenRepository.saveAll(activeTokens);
    }

    public boolean hasActiveTokenForUser(User user) {
        if (user == null || user.getId() == null) {
            return false;
        }

        return !refreshTokenRepository.findByUserIdAndRevokedFalse(user.getId()).isEmpty();
    }
}
