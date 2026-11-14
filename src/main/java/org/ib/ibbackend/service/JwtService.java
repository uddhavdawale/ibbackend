package org.ib.ibbackend.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.ib.ibbackend.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {
    private static final String DEFAULT_ACCESS_SECRET = "dev-access-secret-change-me-please-32-plus-bytes-64-chars-long-123456";
    private static final String DEFAULT_REFRESH_SECRET = "dev-refresh-secret-change-me-please-32-plus-bytes-64-chars-long-654321";

    private final SecretKey secretKey;
    private final SecretKey refreshTokenSecretKey;
    private final long accessTokenExpiryMs;
    private final long refreshTokenExpiryMs;

    public JwtService() {
        this(
                "dev-access-secret-change-me-please-32-plus-bytes-64-chars-long-123456",
                "dev-refresh-secret-change-me-please-32-plus-bytes-64-chars-long-654321",
                30L * 60L * 1000L,
                7L * 24L * 60L * 60L * 1000L
        );
    }

    public JwtService(
            @Value("${app.jwt.access-secret:dev-access-secret-change-me-please-32-plus-bytes}") String accessSecret,
            @Value("${app.jwt.refresh-secret:dev-refresh-secret-change-me-please-32-plus-bytes}") String refreshSecret,
            @Value("${app.jwt.access-token-expiry-ms:1800000}") long accessTokenExpiryMs,
            @Value("${app.jwt.refresh-token-expiry-ms:604800000}") long refreshTokenExpiryMs) {

        String resolvedAccessSecret = (accessSecret == null || accessSecret.isBlank()) ? DEFAULT_ACCESS_SECRET : accessSecret;
        String resolvedRefreshSecret = (refreshSecret == null || refreshSecret.isBlank()) ? DEFAULT_REFRESH_SECRET : refreshSecret;

        validateSecretSize("JWT access secret", resolvedAccessSecret, 64);
        validateSecretSize("JWT refresh secret", resolvedRefreshSecret, 64);

        this.secretKey = Keys.hmacShaKeyFor(resolvedAccessSecret.getBytes(StandardCharsets.UTF_8));
        this.refreshTokenSecretKey = Keys.hmacShaKeyFor(resolvedRefreshSecret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMs = accessTokenExpiryMs > 0 ? accessTokenExpiryMs : 30L * 60L * 1000L;
        this.refreshTokenExpiryMs = refreshTokenExpiryMs > 0 ? refreshTokenExpiryMs : 1L * 24L * 60L * 60L * 1000L;
    }

    private void validateSecretSize(String name, String value, int minimumBytes) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < minimumBytes) {
            throw new IllegalArgumentException(name + " must be at least " + minimumBytes + " bytes long for HS512");
        }
    }

    public String generateToken(User user) {
        return generateAccessToken(user);
    }

    public String generateAccessToken(User user) {
        var builder = Jwts.builder()
                .setSubject(user.getEmail())
                .claim("role", user.getRole())
                .claim("tokenType", "access")
                .claim("authVersion", user.getAuthVersion())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessTokenExpiryMs));

        if (user.getTenant() != null) {
            builder.claim("tenantId", Long.valueOf(user.getTenant().getId()));
        }

        return builder.signWith(SignatureAlgorithm.HS512, secretKey).compact();
    }

    public String generateRefreshToken(User user) {
        var builder = Jwts.builder()
                .setSubject(user.getEmail())
                .claim("role", user.getRole())
                .claim("tokenType", "refresh")
                .claim("authVersion", user.getAuthVersion())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + refreshTokenExpiryMs));

        if (user.getTenant() != null) {
            builder.claim("tenantId", Long.valueOf(user.getTenant().getId()));
        }

        return builder.signWith(SignatureAlgorithm.HS512, refreshTokenSecretKey).compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public Claims extractRefreshClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(refreshTokenSecretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public Long getTenantId(String token) {
        Object tenantId = extractClaims(token).get("tenantId");
        if (tenantId == null) {
            return null;
        }
        if (tenantId instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(tenantId.toString());
    }

    public Long getRefreshTenantId(String token) {
        Object tenantId = extractRefreshClaims(token).get("tenantId");
        if (tenantId == null) {
            return null;
        }
        if (tenantId instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(tenantId.toString());
    }

    public boolean isTokenValid(String token, User user) {
        return isAccessTokenValid(token, user);
    }

    public boolean isAccessTokenValid(String token, User user) {
        try {
            final String username = extractClaims(token).getSubject();
            return username.equals(user.getEmail())
                    && hasCurrentAuthVersion(extractClaims(token), user)
                    && !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isRefreshTokenValid(String token, User user) {
        try {
            final String username = extractRefreshClaims(token).getSubject();
            return username.equals(user.getEmail())
                    && hasCurrentAuthVersion(extractRefreshClaims(token), user)
                    && !isRefreshTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    private boolean isTokenExpired(String token) {
        return extractClaims(token).getExpiration().before(new Date());
    }

    private boolean isRefreshTokenExpired(String token) {
        return extractRefreshClaims(token).getExpiration().before(new Date());
    }

    private boolean hasCurrentAuthVersion(Claims claims, User user) {
        Object value = claims.get("authVersion");
        long tokenVersion = value instanceof Number number ? number.longValue() : 0L;
        return tokenVersion == user.getAuthVersion();
    }
}