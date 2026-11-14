package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {

    private org.ib.ibbackend.repository.RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;

    private JwtService jwtService;
    private final Map<String, org.ib.ibbackend.entity.RefreshToken> tokens = new HashMap<>();

    @BeforeEach
    void setUp() {
        refreshTokenRepository = mock(org.ib.ibbackend.repository.RefreshTokenRepository.class);
        jwtService = new JwtService(
                "dev-access-secret-change-me-please-32-plus-bytes-64-chars-long-123456",
                "dev-refresh-secret-change-me-please-32-plus-bytes-64-chars-long-654321",
                30L * 60L * 1000L,
                7L * 24L * 60L * 60L * 1000L
        );
        refreshTokenService = new RefreshTokenService(refreshTokenRepository, jwtService);
        tokens.clear();
        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(tokens.get(invocation.getArgument(0))));
        when(refreshTokenRepository.findByTokenHashForUpdate(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(tokens.get(invocation.getArgument(0))));
        when(refreshTokenRepository.save(any())).thenAnswer(invocation -> {
            org.ib.ibbackend.entity.RefreshToken token = invocation.getArgument(0);
            tokens.put(token.getTokenHash(), token);
            return token;
        });
    }

    @Test
    void shouldRejectRevokedRefreshToken() {
        User user = new User();
        user.setId(1L);
        user.setEmail("owner@example.com");

        String refreshToken = jwtService.generateRefreshToken(user);

        org.ib.ibbackend.entity.RefreshToken storedToken = new org.ib.ibbackend.entity.RefreshToken();
        storedToken.setUser(user);
        storedToken.setTokenHash(refreshTokenService.hashToken(refreshToken));
        storedToken.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(7));
        storedToken.setRevoked(false);

        tokens.put(storedToken.getTokenHash(), storedToken);

        refreshTokenService.revokeToken(refreshToken);

        assertThat(refreshTokenService.isValidForUser(user, refreshToken)).isFalse();
    }

    @Test
    void shouldReturnSameRotatedTokenForConcurrentRefreshAttempts() {
        User user = new User();
        user.setId(2L);
        user.setEmail("owner@example.com");

        String originalToken = jwtService.generateRefreshToken(user);
        String rotatedToken = jwtService.generateRefreshToken(user);
        String concurrentCandidate = jwtService.generateRefreshToken(user);

        org.ib.ibbackend.entity.RefreshToken storedToken = new org.ib.ibbackend.entity.RefreshToken();
        storedToken.setUser(user);
        storedToken.setTokenHash(refreshTokenService.hashToken(originalToken));
        storedToken.setFamilyId("family-1");
        storedToken.setExpiresAt(jwtService.extractRefreshClaims(originalToken).getExpiration()
                .toInstant().atZone(ZoneOffset.UTC).toLocalDateTime());
        storedToken.setRevoked(false);
        tokens.put(storedToken.getTokenHash(), storedToken);

        assertThat(refreshTokenService.rotateToken(user, originalToken, rotatedToken))
                .contains(rotatedToken);
        assertThat(refreshTokenService.rotateToken(user, originalToken, concurrentCandidate))
                .contains(rotatedToken);

        assertThat(refreshTokenService.isValidForUser(user, originalToken)).isFalse();
        verify(refreshTokenRepository, never()).revokeFamily(anyString(), any());
    }

    @Test
    void shouldRevokeTokenFamilyWhenRefreshTokenIsReusedOutsideGracePeriod() {
        User user = new User();
        user.setId(4L);
        user.setEmail("owner@example.com");
        String originalToken = jwtService.generateRefreshToken(user);
        String rotatedToken = jwtService.generateRefreshToken(user);
        org.ib.ibbackend.entity.RefreshToken storedToken = new org.ib.ibbackend.entity.RefreshToken();
        storedToken.setUser(user);
        storedToken.setTokenHash(refreshTokenService.hashToken(originalToken));
        storedToken.setFamilyId("family-2");
        storedToken.setExpiresAt(jwtService.extractRefreshClaims(originalToken).getExpiration()
                .toInstant().atZone(ZoneOffset.UTC).toLocalDateTime());
        tokens.put(storedToken.getTokenHash(), storedToken);

        refreshTokenService.rotateToken(user, originalToken, rotatedToken);
        storedToken.setReplacementGraceUntil(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));

        assertThat(refreshTokenService.rotateToken(user, originalToken, jwtService.generateRefreshToken(user)))
                .isEmpty();
        verify(refreshTokenRepository).revokeFamily(eq("family-2"), any());
    }

    @Test
    void shouldRevokeAllActiveTokensForUserOnLogout() {
        User user = new User();
        user.setId(3L);
        user.setEmail("owner@example.com");

        refreshTokenService.revokeAllActiveTokensForUser(user);

        verify(refreshTokenRepository).revokeAllActiveForUser(any(), any());
    }
}
