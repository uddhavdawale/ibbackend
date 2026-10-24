package org.ib.ibbackend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        try {
            String authHeader = req.getHeader(HttpHeaders.AUTHORIZATION);

            if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7).trim();
                String username = jwtService.extractUsername(token);
                Long tenantId = jwtService.getTenantId(token);

                if (StringUtils.hasText(username)) {
                    User user = userRepository.findByEmail(username).orElse(null);
                    if (user == null) {
                        throw new SecurityException("User not found for token");
                    }

                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList())
                    );
                    log.debug("JWT authenticated user={} tenantId={} for request {}", username, tenantId, req.getRequestURI());
                }

                if (tenantId != null) {
                    TenantContext.setCurrentTenant(tenantId);
                }
            } else if (StringUtils.hasText(authHeader)) {
                log.warn("Authorization header was present but not in Bearer format for request {}", req.getRequestURI());
            }

            chain.doFilter(req, res);
        } catch (Exception e) {
            log.warn("Invalid or expired bearer token for request {}", req.getRequestURI(), e);
            SecurityContextHolder.clearContext();
            TenantContext.clear();
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"message\":\"Invalid or expired token\"}");
            return;
        }
    }
}