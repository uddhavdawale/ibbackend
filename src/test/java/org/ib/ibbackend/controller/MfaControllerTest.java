package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.MfaOtpRequest;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.FirebaseTokenVerifierService;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.MailMfaService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MfaControllerTest {

    private MfaController controller;
    private UserRepository userRepository;
    private MailMfaService mailMfaService;
    private JwtService jwtService;
    private RefreshTokenService refreshTokenService;
    private FirebaseTokenVerifierService firebaseTokenVerifierService;

    @BeforeEach
    void setUp() {
        controller = new MfaController();
        userRepository = mock(UserRepository.class);
        mailMfaService = mock(MailMfaService.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        firebaseTokenVerifierService = mock(FirebaseTokenVerifierService.class);

        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "mailMfaService", mailMfaService);
        ReflectionTestUtils.setField(controller, "jwtService", jwtService);
        ReflectionTestUtils.setField(controller, "refreshTokenService", refreshTokenService);
        ReflectionTestUtils.setField(controller, "firebaseTokenVerifierService", firebaseTokenVerifierService);
    }

    @Test
    void verifyOtpShouldLoginWhenFirebaseTokenMatchesUserEmail() throws Exception {
        User user = buildUser("owner@example.com", "9876543210");
        MfaOtpRequest request = new MfaOtpRequest();
        request.setUsername("owner@example.com");
        request.setFirebaseIdToken("firebase-id-token");

        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(firebaseTokenVerifierService.isEnabled()).thenReturn(true);
        when(firebaseTokenVerifierService.verifyIdToken("firebase-id-token"))
                .thenReturn(new FirebaseTokenVerifierService.FirebaseIdentity("uid-1", "owner@example.com", null, true));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh-token");

        ResponseEntity<Map<String, Object>> response = controller.verifyOtp(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getBody()).containsEntry("accessToken", "access-token");
        assertThat(response.getBody()).containsEntry("refreshToken", "refresh-token");
        assertThat(response.getHeaders().getFirst("Set-Cookie")).contains("refreshToken=refresh-token");
    }

    @Test
    void verifyOtpShouldRejectWhenFirebaseIdentityDoesNotMatchUser() throws Exception {
        User user = buildUser("owner@example.com", "9876543210");
        MfaOtpRequest request = new MfaOtpRequest();
        request.setUsername("owner@example.com");
        request.setFirebaseIdToken("firebase-id-token");

        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(firebaseTokenVerifierService.isEnabled()).thenReturn(true);
        when(firebaseTokenVerifierService.verifyIdToken("firebase-id-token"))
                .thenReturn(new FirebaseTokenVerifierService.FirebaseIdentity("uid-1", "other@example.com", "+10000000000", true));

        ResponseEntity<Map<String, Object>> response = controller.verifyOtp(request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).containsEntry("message", "Firebase identity mismatch");
    }

    @Test
    void verifyOtpShouldRejectInvalidFirebaseToken() throws Exception {
        User user = buildUser("owner@example.com", "9876543210");
        MfaOtpRequest request = new MfaOtpRequest();
        request.setUsername("owner@example.com");
        request.setFirebaseIdToken("bad-token");

        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(firebaseTokenVerifierService.isEnabled()).thenReturn(true);
        when(firebaseTokenVerifierService.verifyIdToken("bad-token"))
                .thenThrow(new IllegalArgumentException("invalid"));

        ResponseEntity<Map<String, Object>> response = controller.verifyOtp(request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).containsEntry("message", "Invalid Firebase OTP token");
    }

    @Test
    void verifyOtpShouldFallbackToMailOtpWhenFirebaseDisabled() {
        User user = buildUser("owner@example.com", "9876543210");
        MfaOtpRequest request = new MfaOtpRequest();
        request.setUsername("owner@example.com");
        request.setOtp("123456");

        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(firebaseTokenVerifierService.isEnabled()).thenReturn(false);
        when(mailMfaService.isEnabled()).thenReturn(true);
        when(mailMfaService.verifyOtp(eq("owner@example.com"), eq("123456"))).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh-token");

        ResponseEntity<Map<String, Object>> response = controller.verifyOtp(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true);
    }

    @Test
    void sendOtpShouldInformFirebaseProviderWhenMailMfaIsDisabled() {
        User user = buildUser("owner@example.com", "9876543210");
        Map<String, String> request = new java.util.HashMap<>();
        request.put("username", "owner@example.com");

        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(firebaseTokenVerifierService.isEnabled()).thenReturn(true);
        when(mailMfaService.isEnabled()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.sendOtp(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("provider", "FIREBASE");
        assertThat(response.getBody()).containsEntry("message", "Firebase MFA is enabled. Use the Firebase verification flow.");
    }

    @Test
    void sendOtpShouldStillAllowMailPathWhenFirebaseIsEnabled() {
        User user = buildUser("owner@example.com", "9876543210");
        Map<String, String> request = new java.util.HashMap<>();
        request.put("username", "owner@example.com");

        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(firebaseTokenVerifierService.isEnabled()).thenReturn(true);
        when(mailMfaService.isEnabled()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.sendOtp(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("provider", "FIREBASE");
        assertThat(response.getBody()).containsEntry("message", "Firebase MFA is enabled. Use the Firebase verification flow.");
    }

    @Test
    void verifyOtpShouldReturnForbiddenWhenNoVerifierEnabled() {
        User user = buildUser("owner@example.com", "9876543210");
        MfaOtpRequest request = new MfaOtpRequest();
        request.setUsername("owner@example.com");

        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(firebaseTokenVerifierService.isEnabled()).thenReturn(false);
        when(mailMfaService.isEnabled()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.verifyOtp(request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).containsEntry("message", "No OTP verifier enabled. Enable Firebase or Mail MFA");
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

