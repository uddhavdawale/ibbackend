package org.ib.ibbackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AuthCookieService {
    private static final String REFRESH_COOKIE_PATH = "/api/auth";
    private static final Duration DEFAULT_REFRESH_COOKIE_AGE = Duration.ofDays(7);

    private final boolean secure;
    private final String sameSite;

    public AuthCookieService(
            @Value("${app.security.refresh-cookie.secure:true}") boolean secure,
            @Value("${app.security.refresh-cookie.same-site:None}") String sameSite) {
        if (!"Lax".equalsIgnoreCase(sameSite)
                && !"Strict".equalsIgnoreCase(sameSite)
                && !"None".equalsIgnoreCase(sameSite)) {
            throw new IllegalArgumentException("Refresh-cookie SameSite must be Lax, Strict, or None");
        }
        if ("None".equalsIgnoreCase(sameSite) && !secure) {
            throw new IllegalArgumentException("SameSite=None refresh cookies require Secure");
        }
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public static AuthCookieService defaultFactory() {
        return new AuthCookieService(true, "None");
    }

    public ResponseCookie refreshCookie(String token, long expiryMillis) {
        Duration maxAge = expiryMillis > 0
                ? Duration.ofMillis(expiryMillis)
                : DEFAULT_REFRESH_COOKIE_AGE;
        return ResponseCookie.from("refreshToken", token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    public ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }

    public ResponseCookie clearLegacyRefreshCookie() {
        return ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }
}
