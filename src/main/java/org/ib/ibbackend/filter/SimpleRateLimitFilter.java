package org.ib.ibbackend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ib.ibbackend.service.AuditLogService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SimpleRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_SECONDS = 60;
    private static final int MAX_COUNTERS = 10_000;
    private static final int MAX_REQUESTS_PER_WINDOW = 60;
    private static final int MAX_EXPORT_REQUESTS_PER_WINDOW = 20;
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/login",
            "/login",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/mfa/send",
            "/api/mfa/cancel",
            "/api/mfa/verify",
            "/api/auth/password/forgot",
            "/api/auth/password/reset"
    );
    private static final Set<String> EXPORT_PATH_PREFIXES = Set.of(
            "/api/dashboard",
            "/api/gst",
            "/api/audit/export"
    );

    private final AuditLogService auditLogService;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final AtomicLong lastPrunedAt = new AtomicLong();

    public SimpleRateLimitFilter(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isProtectedExportPath(path)) {
            if (!checkRateLimitAndContinue(request, response, path, MAX_EXPORT_REQUESTS_PER_WINDOW)) {
                return;
            }
        }

        if (!PROTECTED_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!checkRateLimitAndContinue(request, response, path, limitForPath(path))) {
            return;
        }

        filterChain.doFilter(request, response);
    }

    private int limitForPath(String path) {
        return switch (path) {
            case "/api/auth/password/forgot" -> 5;
            case "/api/auth/password/reset", "/api/mfa/send", "/api/mfa/cancel", "/api/mfa/verify" -> 10;
            case "/api/login", "/login", "/api/auth/login" -> 15;
            default -> MAX_REQUESTS_PER_WINDOW;
        };
    }

    private boolean checkRateLimitAndContinue(HttpServletRequest request,
                                             HttpServletResponse response,
                                             String path,
                                             int maxRequests) throws IOException {
        pruneExpiredCounters();
        if (counters.size() >= MAX_COUNTERS) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many requests. Please retry shortly.\"}");
            return false;
        }
        String ip = extractClientIp(request);
        String key = ip + "|" + path;

        Counter counter = counters.compute(key, (k, existing) -> {
            long now = Instant.now().getEpochSecond();
            if (existing == null || now - existing.windowStart >= WINDOW_SECONDS) {
                return new Counter(now);
            }
            return existing;
        });

        int current = counter.count.incrementAndGet();
        if (current > maxRequests) {
            auditLogService.denied("RATE_LIMIT_TRIGGER", path, "Rate limit exceeded for protected auth endpoint");
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many requests. Please retry shortly.\"}");
            return false;
        }

        return true;
    }

    private void pruneExpiredCounters() {
        long now = Instant.now().getEpochSecond();
        long last = lastPrunedAt.get();
        if (now - last < WINDOW_SECONDS || !lastPrunedAt.compareAndSet(last, now)) {
            return;
        }
        counters.entrySet().removeIf(entry -> now - entry.getValue().windowStart >= WINDOW_SECONDS);
    }

    private boolean isProtectedExportPath(String path) {
        for (String prefix : EXPORT_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            if (parts.length > 0 && !parts[0].isBlank()) {
                return parts[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static final class Counter {
        private final long windowStart;
        private final AtomicInteger count = new AtomicInteger(0);

        private Counter(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
