package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.LoginRequest;
import org.ib.ibbackend.dto.LoginResponse;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.AuthChallengeService;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.MailMfaService;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginControllerMfaContractTest {
    @Test
    void mailMfaLoginReturnsOnlyOpaqueChallengeBeforeAuthentication() {
        LoginController controller = new LoginController();
        UserRepository userRepository = mock(UserRepository.class);
        MailMfaService mailMfaService = mock(MailMfaService.class);
        AuthChallengeService authChallengeService = mock(AuthChallengeService.class);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "tenantRepository", mock(TenantRepository.class));
        ReflectionTestUtils.setField(controller, "passwordSecurityService",
                new PasswordSecurityService(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        ReflectionTestUtils.setField(controller, "jwtService", mock(JwtService.class));
        ReflectionTestUtils.setField(controller, "refreshTokenService", mock(RefreshTokenService.class));
        ReflectionTestUtils.setField(controller, "mailMfaService", mailMfaService);
        ReflectionTestUtils.setField(controller, "authChallengeService", authChallengeService);

        User user = new User();
        user.setId(9L);
        user.setEmail("owner@example.com");
        user.setOwnerName("Owner");
        user.setBusinessName("Store");
        user.setRole("STORE_ADMIN");
        user.setPasswordHash(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("Correct Horse"));
        Tenant tenant = new Tenant();
        tenant.setId(3);
        user.setTenant(tenant);
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(mailMfaService.isEnabled()).thenReturn(true);
        when(mailMfaService.isReady()).thenReturn(true);
        when(authChallengeService.createChallenge(user)).thenReturn(new AuthChallengeService.IssuedChallenge(
                "opaque-challenge", "preauth-secret", "o***@example.com",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)));

        LoginRequest request = new LoginRequest();
        request.setUsername("owner@example.com");
        request.setPassword("Correct Horse");

        ResponseEntity<LoginResponse> response = controller.login(request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isInstanceOf(org.ib.ibbackend.dto.MfaLoginChallengeResponse.class);
        org.ib.ibbackend.dto.MfaLoginChallengeResponse challengeResponse =
                (org.ib.ibbackend.dto.MfaLoginChallengeResponse) response.getBody();
        assertThat(challengeResponse.isMfaRequired()).isTrue();
        assertThat(challengeResponse.getMfaChallenge())
                .containsEntry("challengeId", "opaque-challenge")
                .containsEntry("maskedDestination", "o***@example.com");
        assertThat(response.getHeaders().getFirst("Set-Cookie"))
                .contains("mfaPreAuth=preauth-secret")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=None");
    }

    @Test
    void systemAdminMfaLoginDoesNotCreateATenant() {
        LoginController controller = new LoginController();
        UserRepository userRepository = mock(UserRepository.class);
        TenantRepository tenantRepository = mock(TenantRepository.class);
        MailMfaService mailMfaService = mock(MailMfaService.class);
        AuthChallengeService authChallengeService = mock(AuthChallengeService.class);
        Argon2PasswordEncoder passwordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "tenantRepository", tenantRepository);
        ReflectionTestUtils.setField(controller, "passwordSecurityService", new PasswordSecurityService(passwordEncoder));
        ReflectionTestUtils.setField(controller, "jwtService", mock(JwtService.class));
        ReflectionTestUtils.setField(controller, "refreshTokenService", mock(RefreshTokenService.class));
        ReflectionTestUtils.setField(controller, "mailMfaService", mailMfaService);
        ReflectionTestUtils.setField(controller, "authChallengeService", authChallengeService);

        User user = new User();
        user.setId(10L);
        user.setEmail("system-admin@example.com");
        user.setOwnerName("System Admin");
        user.setRole("SYSTEM_ADMIN");
        user.setPasswordHash(passwordEncoder.encode("Correct Horse"));
        when(userRepository.findByEmail("system-admin@example.com")).thenReturn(Optional.of(user));
        when(mailMfaService.isEnabled()).thenReturn(true);
        when(mailMfaService.isReady()).thenReturn(true);
        when(authChallengeService.createChallenge(user)).thenReturn(new AuthChallengeService.IssuedChallenge(
                "system-admin-challenge", "preauth-secret", "s***@example.com",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)));

        LoginRequest request = new LoginRequest();
        request.setUsername("system-admin@example.com");
        request.setPassword("Correct Horse");

        ResponseEntity<LoginResponse> response = controller.login(request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(user.getTenant()).isNull();
        verify(tenantRepository, never()).save(org.mockito.ArgumentMatchers.any(Tenant.class));
        verify(userRepository, never()).save(user);
    }
}
