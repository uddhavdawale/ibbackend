package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.ApiResponse;
import org.ib.ibbackend.dto.SignupRequest;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.PasswordSecurityService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.ib.ibbackend.service.UserProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicLong;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthControllerTenantTest {

    @Mock
    private UserRepository userRepo;

    @Mock
    private TenantRepository tenantRepo;

    @Mock
    private PasswordSecurityService passwordSecurityService;

    @Mock
    private JwtService jwtService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        UserProvisioningService provisioningService = new UserProvisioningService(tenantRepo, passwordSecurityService);
        ReflectionTestUtils.setField(authController, "userProvisioningService", provisioningService);
        SecurityContextHolder.clearContext();
    }

    @Test
    void signup_shouldCreateUniqueTenantForEachUser() {
        when(passwordSecurityService.hashNewPassword(anyString())).thenReturn("hashedPassword");

        AtomicLong idSequence = new AtomicLong(1000L);

        SignupRequest firstRequest = new SignupRequest();
        firstRequest.setBusinessName("Demo Supermart");
        firstRequest.setOwnerName("demo");
        firstRequest.setUsername("demo_owner_1");
        firstRequest.setEmail("demo@example.com");
        firstRequest.setPasswordHash("plainPassword");
        firstRequest.setRole("STORE_ADMIN");

        SignupRequest secondRequest = new SignupRequest();
        secondRequest.setBusinessName("Demo Supermart");
        secondRequest.setOwnerName("demo-two");
        secondRequest.setUsername("demo_owner_2");
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

    @Test
    void signup_shouldRejectPasswordShorterThan12Characters() {
        SignupRequest request = new SignupRequest();
        request.setBusinessName("Demo Supermart");
        request.setOwnerName("demo");
        request.setUsername("demo_owner_short");
        request.setEmail("demo@example.com");
        request.setPasswordHash("short123");
        request.setRole("STORE_ADMIN");

        when(userRepo.existsByEmail("demo@example.com")).thenReturn(false);
        when(passwordSecurityService.hashNewPassword("short123"))
                .thenThrow(new IllegalArgumentException("Password must be at least 12 characters long"));

        ResponseEntity<ApiResponse> response = authController.signup(request);

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertTrue(response.getBody().getMessage().contains("at least 12"));
        verify(userRepo, never()).save(any(User.class));
    }

    @Test
    void signup_authenticatedStoreAdmin_shouldForceCreatorTenantAndOwner() {
        Tenant creatorTenant = new Tenant();
        creatorTenant.setId(77);
        creatorTenant.setCode("TEN77");
        creatorTenant.setName("Store 77");

        User creator = new User();
        creator.setId(900L);
        creator.setEmail("admin77@example.com");
        creator.setOwnerName("Store Owner");
        creator.setBusinessName("Store 77");
        creator.setRole("STORE_ADMIN");
        creator.setTenant(creatorTenant);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin77@example.com", null)
        );

        SignupRequest childRequest = new SignupRequest();
        childRequest.setBusinessName("Spoofed Business");
        childRequest.setOwnerName("Spoofed Owner");
        childRequest.setUsername("staff77");
        childRequest.setTenantId(77L);
        childRequest.setEmail("staff1@example.com");
        childRequest.setPasswordHash("verySecurePassword123");
        childRequest.setRole("store_billing_viwer");

        when(userRepo.findByEmail("admin77@example.com")).thenReturn(Optional.of(creator));
        when(userRepo.existsByEmail("staff1@example.com")).thenReturn(false);
        when(passwordSecurityService.hashNewPassword("verySecurePassword123")).thenReturn("hashedPassword");

        ResponseEntity<ApiResponse> response = authController.signup(childRequest);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(userCaptor.capture());
        User saved = userCaptor.getValue();

        assertNotNull(saved.getTenant());
        assertEquals(77, saved.getTenant().getId());
        assertEquals("Store Owner", saved.getOwnerName());
        assertEquals("Store 77", saved.getBusinessName());
        assertEquals("staff77", saved.getUsername());
        assertEquals("store_billing_viewer", saved.getRole());
        assertEquals("hashedPassword", saved.getPasswordHash());

        verify(tenantRepo, never()).save(any(Tenant.class));
    }

    @Test
    void signup_authenticatedNonAdmin_shouldReturnForbidden() {
        Tenant creatorTenant = new Tenant();
        creatorTenant.setId(88);

        User creator = new User();
        creator.setId(901L);
        creator.setEmail("staff@example.com");
        creator.setOwnerName("Staff Owner");
        creator.setBusinessName("Store 88");
        creator.setRole("store_inventory_viewer");
        creator.setTenant(creatorTenant);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("staff@example.com", null)
        );

        SignupRequest request = new SignupRequest();
        request.setUsername("new_user88");
        request.setEmail("newuser@example.com");
        request.setPasswordHash("verySecurePassword123");
        request.setRole("store_manager");

        when(userRepo.findByEmail("staff@example.com")).thenReturn(Optional.of(creator));

        ResponseEntity<ApiResponse> response = authController.signup(request);

        assertEquals(403, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        verify(userRepo, never()).save(any(User.class));
    }

    @Test
    void signup_authenticatedAdmin_withForeignTenantInRequest_shouldIgnoreOverrideAndUseCreatorTenant() {
        Tenant creatorTenant = new Tenant();
        creatorTenant.setId(99);

        User creator = new User();
        creator.setId(902L);
        creator.setEmail("admin99@example.com");
        creator.setOwnerName("Owner 99");
        creator.setBusinessName("Store 99");
        creator.setRole("store_admin");
        creator.setTenant(creatorTenant);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin99@example.com", null)
        );

        SignupRequest request = new SignupRequest();
        request.setUsername("new_user99");
        request.setEmail("newuser2@example.com");
        request.setPasswordHash("verySecurePassword123");
        request.setRole("store_manager");
        request.setTenantId(1000L);

        when(userRepo.findByEmail("admin99@example.com")).thenReturn(Optional.of(creator));
        when(userRepo.existsByEmail("newuser2@example.com")).thenReturn(false);
        when(passwordSecurityService.hashNewPassword("verySecurePassword123")).thenReturn("hashedPassword");

        ResponseEntity<ApiResponse> response = authController.signup(request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertNotNull(saved.getTenant());
        assertEquals(99, saved.getTenant().getId());
        assertEquals("Owner 99", saved.getOwnerName());
    }

    @Test
    void signup_withUnsupportedRole_shouldReturnBadRequest() {
        SignupRequest request = new SignupRequest();
        request.setBusinessName("Demo Supermart");
        request.setOwnerName("demo");
        request.setUsername("demo_unknown_role");
        request.setEmail("demo3@example.com");
        request.setPasswordHash("verySecurePassword123");
        request.setRole("custom_unknown_role");

        when(userRepo.existsByEmail("demo3@example.com")).thenReturn(false);
        when(passwordSecurityService.hashNewPassword("verySecurePassword123")).thenReturn("hashedPassword");

        ResponseEntity<ApiResponse> response = authController.signup(request);

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertTrue(response.getBody().getMessage().contains("Unsupported role"));
        verify(userRepo, never()).save(any(User.class));
    }
}
