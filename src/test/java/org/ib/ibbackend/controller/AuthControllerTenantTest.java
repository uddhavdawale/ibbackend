package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.ApiResponse;
import org.ib.ibbackend.dto.SignupRequest;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthControllerTenantTest {

    @Mock
    private UserRepository userRepo;

    @Mock
    private TenantRepository tenantRepo;

    @Mock
    private BCryptPasswordEncoder bcrypt;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void signup_shouldCreateUniqueTenantForEachUser() {
        when(bcrypt.encode(anyString())).thenReturn("hashedPassword");

        AtomicLong idSequence = new AtomicLong(1000L);

        SignupRequest firstRequest = new SignupRequest();
        firstRequest.setBusinessName("Demo Supermart");
        firstRequest.setOwnerName("demo");
        firstRequest.setEmail("demo@example.com");
        firstRequest.setPasswordHash("plainPassword");
        firstRequest.setRole("STORE_ADMIN");

        SignupRequest secondRequest = new SignupRequest();
        secondRequest.setBusinessName("Demo Supermart");
        secondRequest.setOwnerName("demo-two");
        secondRequest.setEmail("demo2@example.com");
        secondRequest.setPasswordHash("plainPassword");
        secondRequest.setRole("STORE_ADMIN");

        when(userRepo.existsByEmail("demo@example.com")).thenReturn(false);
        when(userRepo.existsByEmail("demo2@example.com")).thenReturn(false);
        when(tenantRepo.save(any(Tenant.class))).thenAnswer(invocation -> {
            Tenant tenant = invocation.getArgument(0);
            tenant.setId((int) idSequence.getAndIncrement());
            return tenant;
        });

        ResponseEntity<ApiResponse> firstResponse = authController.signup(firstRequest);
        ResponseEntity<ApiResponse> secondResponse = authController.signup(secondRequest);

        assertTrue(firstResponse.getStatusCode().is2xxSuccessful());
        assertTrue(secondResponse.getStatusCode().is2xxSuccessful());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepo, times(2)).save(userCaptor.capture());

        User firstSavedUser = userCaptor.getAllValues().get(0);
        User secondSavedUser = userCaptor.getAllValues().get(1);

        assertNotNull(firstSavedUser.getTenant());
        assertNotNull(secondSavedUser.getTenant());
        assertNotEquals(firstSavedUser.getTenant().getCode(), secondSavedUser.getTenant().getCode());
        assertNotEquals(firstSavedUser.getTenant().getId(), secondSavedUser.getTenant().getId());
    }
}
