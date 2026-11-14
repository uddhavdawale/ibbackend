package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceRefreshTokenTest {

    @Test
    void shouldGenerateAndValidateRefreshTokens() {
        JwtService jwtService = new JwtService(
                "dev-access-secret-change-me-please-32-plus-bytes-64-chars-long-123456",
                "dev-refresh-secret-change-me-please-32-plus-bytes-64-chars-long-654321",
                30L * 60L * 1000L,
                7L * 24L * 60L * 60L * 1000L
        );

        User user = new User();
        user.setEmail("demo@example.com");
        user.setRole("STORE_ADMIN");

        Tenant tenant = new Tenant();
        tenant.setId(7);
        user.setTenant(tenant);

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        assertThat(jwtService.extractUsername(accessToken)).isEqualTo("demo@example.com");
        assertThat(jwtService.isAccessTokenValid(accessToken, user)).isTrue();
        assertThat(jwtService.isRefreshTokenValid(refreshToken, user)).isTrue();
        assertThat(jwtService.generateRefreshToken(user)).isNotEqualTo(refreshToken);
        assertThat(jwtService.getTenantId(accessToken)).isEqualTo(7L);
        assertThat(jwtService.getRefreshTenantId(refreshToken)).isEqualTo(7L);
    }

    @Test
    void shouldEncryptRefreshTokenReplayCacheWithAuthenticatedEncryption() {
        JwtService jwtService = new JwtService();
        User user = new User();
        user.setEmail("demo@example.com");
        String refreshToken = jwtService.generateRefreshToken(user);

        String encrypted = jwtService.encryptRefreshTokenForReplay(refreshToken);

        assertThat(encrypted).isNotEqualTo(refreshToken);
        assertThat(jwtService.decryptRefreshTokenForReplay(encrypted)).isEqualTo(refreshToken);
    }

    @Test
    void shouldUseLongerDefaultAccessTokenExpiry() {
        JwtService jwtService = new JwtService(
                "dev-access-secret-change-me-please-32-plus-bytes-64-chars-long-123456",
                "dev-refresh-secret-change-me-please-32-plus-bytes-64-chars-long-654321",
                30L * 60L * 1000L,
                7L * 24L * 60L * 60L * 1000L
        );

        User user = new User();
        user.setEmail("demo@example.com");
        user.setRole("STORE_ADMIN");

        String token = jwtService.generateAccessToken(user);
        long expiryDelta = jwtService.extractClaims(token).getExpiration().getTime()
                - jwtService.extractClaims(token).getIssuedAt().getTime();

        assertThat(expiryDelta).isGreaterThanOrEqualTo(30L * 60L * 1000L);
    }

    @Test
    void incrementingAuthVersionInvalidatesIssuedAccessAndRefreshTokens() {
        JwtService jwtService = new JwtService(
                "dev-access-secret-change-me-please-32-plus-bytes-64-chars-long-123456",
                "dev-refresh-secret-change-me-please-32-plus-bytes-64-chars-long-654321",
                30L * 60L * 1000L,
                7L * 24L * 60L * 60L * 1000L
        );
        User user = new User();
        user.setEmail("demo@example.com");

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        user.setAuthVersion(1);

        assertThat(jwtService.isAccessTokenValid(accessToken, user)).isFalse();
        assertThat(jwtService.isRefreshTokenValid(refreshToken, user)).isFalse();
    }
}
