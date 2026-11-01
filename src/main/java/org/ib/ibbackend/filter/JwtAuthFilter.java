package org.ib.ibbackend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository userRepository;

    @Autowired(required = false)
    private RefreshTokenService refreshTokenService;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @Value("${app.security.enforce-active-refresh-session:false}")
    private boolean enforceActiveRefreshSession;

    private void auditFailure(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.failure(action, resourceId, detail);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        boolean tenantContextSet = false;

        try {
            String authHeader = req.getHeader(HttpHeaders.AUTHORIZATION);

            if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7).trim();
                String username = jwtService.extractUsername(token);
                Long tenantId = jwtService.getTenantId(token);

                if (StringUtils.hasText(username)) {
                    User user = userRepository.findByEmail(username)
                            .or(() -> userRepository.findByUsername(username))
                            .orElse(null);
                    if (user == null || !jwtService.isAccessTokenValid(token, user)) {
                        throw new SecurityException("Access token is invalid for user");
                    }
                    if (user.isDisabled() || UserRolePolicy.isDisabledRole(user.getRole())) {
                        if (refreshTokenService != null) {
                            refreshTokenService.revokeAllActiveTokensForUser(user);
                        }
                        throw new SecurityException("User account is disabled");
                    }
                    if (enforceActiveRefreshSession
                            && refreshTokenService != null
                            && !refreshTokenService.hasActiveTokenForUser(user)) {
                        throw new SecurityException("User session has been revoked");
                    }

                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList())
                    );
                    log.debug("JWT authenticated user={} tenantId={} for request {}", username, tenantId, req.getRequestURI());
                }

                if (tenantId != null) {
                    TenantContext.setCurrentTenant(tenantId);
                    tenantContextSet = true;
                }
            } else if (StringUtils.hasText(authHeader)) {
                log.warn("Authorization header was present but not in Bearer format for request {}", req.getRequestURI());
                auditFailure("ACCESS_DENIED_EVENT", req.getRequestURI(), "Authorization header is not Bearer format");
            }
        } catch (Exception e) {
            log.warn("Invalid or expired bearer token for request {}", req.getRequestURI(), e);
            auditFailure("ACCESS_DENIED_EVENT", req.getRequestURI(), "Invalid or expired bearer token");
            SecurityContextHolder.clearContext();
            TenantContext.clear();
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"message\":\"Invalid or expired token\"}");
            return;
        }

        try {
            chain.doFilter(req, res);
        } finally {
            if (tenantContextSet || TenantContext.getCurrentTenant() != null) {
                TenantContext.clear();
            }
        }
    }
}