package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.LoginRequest;
import org.ib.ibbackend.dto.LoginResponse;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.LoginCryptoService;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginControllerCryptoContractTest {

    @Test
    void encryptedLoginShouldSucceedWhenEncryptedOnlyIsEnabled() {
        LoginController controller = new LoginController();
        UserRepository userRepo = mock(UserRepository.class);
        JwtService jwtService = mock(JwtService.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        LoginCryptoService loginCryptoService = mock(LoginCryptoService.class);
        Argon2PasswordEncoder argon2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

        ReflectionTestUtils.setField(controller, "userRepository", userRepo);
        ReflectionTestUtils.setField(controller, "tenantRepository", mock(TenantRepository.class));
        ReflectionTestUtils.setField(controller, "passwordSecurityService", new PasswordSecurityService(argon2));
        ReflectionTestUtils.setField(controller, "jwtService", jwtService);
        ReflectionTestUtils.setField(controller, "refreshTokenService", refreshTokenService);
        ReflectionTestUtils.setField(controller, "loginCryptoService", loginCryptoService);
        ReflectionTestUtils.setField(controller, "encryptedLoginOnly", true);

        Tenant tenant = new Tenant();
        tenant.setId(11);

        User user = new User();
        user.setId(77L);
        user.setEmail("retail@example.com");
        user.setOwnerName("Retail Owner");
        user.setBusinessName("Retail Store");
        user.setRole("STORE_ADMIN");
        user.setPasswordHash(argon2.encode("secret"));
        user.setTenant(tenant);

        when(userRepo.findByEmail("retail@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh-token");

        LoginRequest encryptedRequest = new LoginRequest();
        encryptedRequest.setEncryptedPayload("payload");
        encryptedRequest.setEncryptedKey("key");
        encryptedRequest.setIv("iv");

        LoginRequest decryptedRequest = new LoginRequest();
        decryptedRequest.setUsername("retail@example.com");
        decryptedRequest.setPassword("secret");

        when(loginCryptoService.isEncryptedPayload(encryptedRequest)).thenReturn(true);
        when(loginCryptoService.resolveLoginRequest(encryptedRequest)).thenReturn(decryptedRequest);

        ResponseEntity<LoginResponse> response = controller.login(encryptedRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getAccessToken()).isEqualTo("access-token");
    }

    @Test
    void plaintextLoginShouldBeRejectedWhenEncryptedOnlyIsEnabled() {
        LoginController controller = new LoginController();
        LoginCryptoService loginCryptoService = mock(LoginCryptoService.class);

        ReflectionTestUtils.setField(controller, "userRepository", mock(UserRepository.class));
        ReflectionTestUtils.setField(controller, "tenantRepository", mock(TenantRepository.class));
        ReflectionTestUtils.setField(controller, "passwordSecurityService", new PasswordSecurityService(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        ReflectionTestUtils.setField(controller, "jwtService", mock(JwtService.class));
        ReflectionTestUtils.setField(controller, "refreshTokenService", mock(RefreshTokenService.class));
        ReflectionTestUtils.setField(controller, "loginCryptoService", loginCryptoService);
        ReflectionTestUtils.setField(controller, "encryptedLoginOnly", true);

        LoginRequest plaintext = new LoginRequest();
        plaintext.setUsername("retail@example.com");
        plaintext.setPassword("secret");

        when(loginCryptoService.isEncryptedPayload(plaintext)).thenReturn(false);

        ResponseEntity<LoginResponse> response = controller.login(plaintext);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @Test
    void plaintextLoginShouldRemainSupportedWhenEncryptedOnlyIsDisabled() {
        LoginController controller = new LoginController();
        UserRepository userRepo = mock(UserRepository.class);
        JwtService jwtService = mock(JwtService.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        Argon2PasswordEncoder argon2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

        ReflectionTestUtils.setField(controller, "userRepository", userRepo);
        ReflectionTestUtils.setField(controller, "tenantRepository", mock(TenantRepository.class));
        ReflectionTestUtils.setField(controller, "passwordSecurityService", new PasswordSecurityService(argon2));
        ReflectionTestUtils.setField(controller, "jwtService", jwtService);
        ReflectionTestUtils.setField(controller, "refreshTokenService", refreshTokenService);
        ReflectionTestUtils.setField(controller, "encryptedLoginOnly", false);

        Tenant tenant = new Tenant();
        tenant.setId(12);

        User user = new User();
        user.setId(78L);
        user.setEmail("plain@example.com");
        user.setOwnerName("Retail Owner");
        user.setBusinessName("Retail Store");
        user.setRole("STORE_ADMIN");
        user.setPasswordHash(argon2.encode("secret"));
        user.setTenant(tenant);

        when(userRepo.findByEmail("plain@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user)).thenReturn("refresh-token");

        LoginRequest plaintext = new LoginRequest();
        plaintext.setUsername("plain@example.com");
        plaintext.setPassword("secret");

        ResponseEntity<LoginResponse> response = controller.login(plaintext);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
    }
}

