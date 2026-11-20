package org.ib.ibbackend.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.ib.ibbackend.dto.MfaChallengeRequest;
import org.ib.ibbackend.dto.MfaOtpRequest;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.JwtAuthFilter;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.AuthChallengeService;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.MailMfaService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MfaControllerTest {
    private MfaController controller;
    private UserRepository userRepository;
    private MailMfaService mailMfaService;
    private AuthChallengeService authChallengeService;
    private JwtService jwtService;
    private RefreshTokenService refreshTokenService;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        controller = new MfaController();
        userRepository = mock(UserRepository.class);
        mailMfaService = mock(MailMfaService.class);
        authChallengeService = mock(AuthChallengeService.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        httpRequest = mock(HttpServletRequest.class);

        ReflectionTestUtils.setField(controller, "mailMfaService", mailMfaService);
        ReflectionTestUtils.setField(controller, "authChallengeService", authChallengeService);
        ReflectionTestUtils.setField(controller, "jwtService", jwtService);
        ReflectionTestUtils.setField(controller, "refreshTokenService", refreshTokenService);
        ReflectionTestUtils.setField(controller, "allowedOrigins", "http://localhost:5173");
        when(httpRequest.getHeader("Origin")).thenReturn("http://localhost:5173");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void verifyOtpShouldIssueAccessTokenAndOnlyStoreRefreshTokenInCookie() {
        User user = buildUser("owner@example.com", "9876543210");
        MfaOtpRequest request = new MfaOtpRequest();
        request.setChallengeId("challenge-id");
        request.setOtp("123456");

        when(mailMfaService.isEnabled()).thenReturn(true);
        when(authChallengeService.verifyOtp("challenge-id", "preauth-cookie", "123456"))
                .thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh-token");

        ResponseEntity<Map<String, Object>> response = controller.verifyOtp(request, "preauth-cookie", httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getBody()).containsEntry("message", "Login successful");
        assertThat(response.getBody()).containsEntry("token", "access-token");
        assertThat(response.getBody()).containsEntry("accessToken", "access-token");
        assertThat(response.getBody()).containsEntry("username", "owner@example.com");
        assertThat(response.getBody()).containsEntry("role", "STORE_ADMIN");
        assertThat(response.getBody()).containsEntry("email", "owner@example.com");
        assertThat(response.getBody().get("user")).isInstanceOf(Map.class);
        Map<?, ?> authenticatedUser = (Map<?, ?>) response.getBody().get("user");
        assertThat(authenticatedUser.get("username")).isEqualTo("owner@example.com");
        assertThat(authenticatedUser.get("role")).isEqualTo("STORE_ADMIN");
        assertThat(authenticatedUser.containsKey("tenantId")).isTrue();
        assertThat(authenticatedUser.get("tenantId")).isNull();
        assertThat(response.getBody()).doesNotContainKey("refreshToken");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("refreshToken=refresh-token").contains("HttpOnly").contains("SameSite=None"))
                .anySatisfy(cookie -> assertThat(cookie).contains("mfaPreAuth=").contains("Max-Age=0"));
    }

    @Test
    void successfulMfaVerificationTokenAuthenticatesNextProtectedRequest() throws Exception {
        User user = buildUser("retailer@example.com", "9876543210");
        user.setRole("RETAILER");
        Tenant tenant = new Tenant();
        tenant.setId(7);
        user.setTenant(tenant);

        MfaOtpRequest request = new MfaOtpRequest();
        request.setChallengeId("challenge-id");
        request.setOtp("123456");

        JwtService realJwtService = new JwtService();
        ReflectionTestUtils.setField(controller, "jwtService", realJwtService);
        when(mailMfaService.isEnabled()).thenReturn(true);
        when(authChallengeService.verifyOtp("challenge-id", "preauth-cookie", "123456"))
                .thenReturn(Optional.of(user));

        ResponseEntity<Map<String, Object>> verificationResponse =
                controller.verifyOtp(request, "preauth-cookie", httpRequest);

        assertThat(verificationResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(verificationResponse.getBody()).containsEntry("success", true);
        String accessToken = (String) verificationResponse.getBody().get("accessToken");
        assertThat(accessToken).isNotBlank();
        assertThat(realJwtService.isAccessTokenValid(accessToken, user)).isTrue();

        MockHttpServletRequest protectedRequest = new MockHttpServletRequest();
        protectedRequest.setRequestURI("/api/products");
        protectedRequest.setServletPath("/api/products");
        protectedRequest.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        MockHttpServletResponse protectedResponse = new MockHttpServletResponse();
        AtomicReference<String> authenticatedUsername = new AtomicReference<>();
        AtomicLong authenticatedTenantId = new AtomicLong(-1L);
        FilterChain protectedChain = (req, res) -> {
            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                authenticatedUsername.set(SecurityContextHolder.getContext().getAuthentication().getName());
            }
            Long currentTenant = TenantContext.getCurrentTenant();
            authenticatedTenantId.set(currentTenant == null ? -1L : currentTenant);
        };

        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter();
        ReflectionTestUtils.setField(jwtAuthFilter, "jwtService", realJwtService);
        ReflectionTestUtils.setField(jwtAuthFilter, "userRepository", userRepository);
        when(userRepository.findByEmail("retailer@example.com")).thenReturn(Optional.of(user));

        jwtAuthFilter.doFilter(protectedRequest, protectedResponse, protectedChain);

        assertThat(protectedResponse.getStatus()).isEqualTo(200);
        assertThat(authenticatedUsername.get()).isEqualTo("retailer@example.com");
        assertThat(authenticatedTenantId.get()).isEqualTo(7L);
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    void verifyOtpShouldRejectWhenChallengeVerificationFails() {
        MfaOtpRequest request = new MfaOtpRequest();
        request.setChallengeId("challenge-id");
        request.setOtp("000000");
        when(mailMfaService.isEnabled()).thenReturn(true);
        when(authChallengeService.verifyOtp("challenge-id", "cookie", "000000")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.verifyOtp(request, "cookie", httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).containsEntry("message", "MFA verification failed.");
    }

    @Test
    void verifyOtpShouldRejectUsernameOnlyRequests() {
        MfaOtpRequest request = new MfaOtpRequest();
        request.setUsername("owner@example.com");
        request.setOtp("123456");
        when(mailMfaService.isEnabled()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.verifyOtp(request, "cookie", httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(authChallengeService, never()).verifyOtp(any(), any(), any());
    }

    @Test
    void sendOtpShouldRejectRequestsWithoutTrustedOrigin() {
        when(httpRequest.getHeader("Origin")).thenReturn("https://attacker.example");

        ResponseEntity<Map<String, Object>> response = controller.sendOtp(null, null, httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void cancelChallengeShouldInvalidateChallengeAndClearPreAuthCookie() {
        MfaChallengeRequest request = new MfaChallengeRequest();
        request.setChallengeId("challenge-id");

        ResponseEntity<Map<String, Object>> response =
                controller.cancelChallenge(request, "preauth-cookie", httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("mfaPreAuth=").contains("HttpOnly").contains("Max-Age=0"));
    }

    @Test
    void cancelChallengeShouldRejectMissingChallengeId() {
        ResponseEntity<Map<String, Object>> response =
                controller.cancelChallenge(null, "preauth-cookie", httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    private User buildUser(String email, String phone) {
        User user = new User();
        user.setId(1L);
        user.setOwnerName("owner");
        user.setEmail(email);
        user.setPhone(phone);
        user.setRole("STORE_ADMIN");
        return user;
    }
}
