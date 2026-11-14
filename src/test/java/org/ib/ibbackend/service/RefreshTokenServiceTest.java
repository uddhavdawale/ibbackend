package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private org.ib.ibbackend.repository.RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @Test
    void shouldRejectRevokedRefreshToken() {
        User user = new User();
        user.setId(1L);
        user.setEmail("owner@example.com");

        String refreshToken = new JwtService(
                "dev-access-secret-change-me-please-32-plus-bytes-64-chars-long-123456",
                "dev-refresh-secret-change-me-please-32-plus-bytes-64-chars-long-654321",
                30L * 60L * 1000L,
                7L * 24L * 60L * 60L * 1000L
        ).generateRefreshToken(user);

        org.ib.ibbackend.entity.RefreshToken storedToken = new org.ib.ibbackend.entity.RefreshToken();
        storedToken.setUser(user);
        storedToken.setTokenHash(refreshTokenService.hashToken(refreshToken));
        storedToken.setRevoked(false);

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(storedToken));

        refreshTokenService.revokeToken(refreshToken);

        assertThat(refreshTokenService.isValidForUser(user, refreshToken)).isFalse();
    }

    @Test
    void shouldRejectReusedRefreshTokenAfterRotation() {
        User user = new User();
        user.setId(2L);
        user.setEmail("owner@example.com");

        JwtService jwtService = new JwtService(
                "dev-access-secret-change-me-please-32-plus-bytes-64-chars-long-123456",
                "dev-refresh-secret-change-me-please-32-plus-bytes-64-chars-long-654321",
                30L * 60L * 1000L,
                7L * 24L * 60L * 60L * 1000L
        );

        String originalToken = jwtService.generateRefreshToken(user);
        String rotatedToken = jwtService.generateRefreshToken(user);

        org.ib.ibbackend.entity.RefreshToken storedToken = new org.ib.ibbackend.entity.RefreshToken();
        storedToken.setUser(user);
        storedToken.setTokenHash(refreshTokenService.hashToken(originalToken));
        storedToken.setRevoked(false);

        when(refreshTokenRepository.findByTokenHash(refreshTokenService.hashToken(originalToken)))
                .thenReturn(Optional.of(storedToken));

        refreshTokenService.rotateToken(user, originalToken, rotatedToken);

        assertThat(refreshTokenService.isValidForUser(user, originalToken)).isFalse();
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
