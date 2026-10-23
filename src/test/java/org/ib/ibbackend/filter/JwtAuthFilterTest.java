package org.ib.ibbackend.filter;

import jakarta.servlet.FilterChain;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void shouldSetAuthenticationAndTenantFromBearerToken() throws Exception {
        JwtService jwtService = new JwtService();
        Tenant tenant = new Tenant();
        tenant.setId(7);

        User user = new User();
        user.setEmail("retailer@example.com");
        user.setRole("retailer");
        user.setTenant(tenant);

        String token = jwtService.generateToken(user);

        assertThat(jwtService.extractUsername(token)).isEqualTo("retailer@example.com");
        assertThat(jwtService.getTenantId(token)).isEqualTo(7L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
        };

        UserRepository userRepository = mock(UserRepository.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        when(userRepository.findByEmail("retailer@example.com")).thenReturn(java.util.Optional.of(user));
        when(refreshTokenService.hasActiveTokenForUser(user)).thenReturn(true);

        JwtAuthFilter filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "jwtService", jwtService);
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        ReflectionTestUtils.setField(filter, "refreshTokenService", refreshTokenService);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(TenantContext.getCurrentTenant()).isEqualTo(7L);
    }

    @Test
    void shouldRejectRequestsWhenUserSessionWasLoggedOut() throws Exception {
        JwtService jwtService = new JwtService();
        User user = new User();
        user.setEmail("retailer@example.com");
        user.setRole("retailer");

        String token = jwtService.generateToken(user);

        UserRepository userRepository = mock(UserRepository.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        when(userRepository.findByEmail("retailer@example.com")).thenReturn(java.util.Optional.of(user));
        when(refreshTokenService.hasActiveTokenForUser(user)).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new AssertionError("Filter should reject revoked sessions before chain execution");
        };

        JwtAuthFilter filter = new JwtAuthFilter();
        ReflectionTestUtils.setField(filter, "jwtService", jwtService);
        ReflectionTestUtils.setField(filter, "userRepository", userRepository);
        ReflectionTestUtils.setField(filter, "refreshTokenService", refreshTokenService);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
