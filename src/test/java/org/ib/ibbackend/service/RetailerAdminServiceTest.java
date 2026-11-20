package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.SignupRequest;
import org.ib.ibbackend.entity.RetailerStatus;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.controller.RetailerAdminController;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.repository.AuditLogRepository;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetailerAdminServiceTest {

    @AfterEach
    void clearSecurityContext() {
    SecurityContextHolder.clearContext();
    }

    @Test
    void shouldListRetailersPlatformWideForSystemAdminWithoutTenant() {
    UserRepository userRepository = mock(UserRepository.class);
    AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    RetailerAdminService retailerAdminService = mock(RetailerAdminService.class);

    User systemAdmin = new User();
    systemAdmin.setEmail("platform-admin@example.com");
    systemAdmin.setRole("system_admin");

    User retailer = new User();
    retailer.setId(42L);
    retailer.setEmail("retailer@example.com");
    retailer.setRole("retailer");

    when(userRepository.findByEmail("platform-admin@example.com")).thenReturn(Optional.of(systemAdmin));
    when(retailerAdminService.listRetailersForPlatformAdmin(null, null, 0, 20))
        .thenReturn(new PageImpl<>(List.of(retailer), PageRequest.of(0, 20), 1));
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("platform-admin@example.com", "secret"));

    RetailerAdminController controller = new RetailerAdminController(
        retailerAdminService, userRepository, auditLogRepository);
    ResponseEntity<Map<String, Object>> response = controller.listRetailers(0, 20, null, null);

    assertEquals(200, response.getStatusCode().value());
    assertEquals(1L, response.getBody().get("totalItems"));
    verify(retailerAdminService).listRetailersForPlatformAdmin(null, null, 0, 20);
    }

    @Test
    void shouldDenyTenantAdminAccessToPlatformRetailerList() {
    UserRepository userRepository = mock(UserRepository.class);
    AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    RetailerAdminService retailerAdminService = mock(RetailerAdminService.class);

    User storeAdmin = new User();
    storeAdmin.setEmail("store-admin@example.com");
    storeAdmin.setRole("store_admin");
    when(userRepository.findByEmail("store-admin@example.com")).thenReturn(Optional.of(storeAdmin));
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("store-admin@example.com", "secret"));

    RetailerAdminController controller = new RetailerAdminController(
        retailerAdminService, userRepository, auditLogRepository);

    assertThrows(AccessDeniedException.class, () -> controller.listRetailers(0, 20, null, null));
    verify(retailerAdminService, never()).listRetailersForPlatformAdmin(any(), any(), eq(0), eq(20));
    }

    @Test
    void shouldApproveRetailerAndKeepExistingBehaviorSafe() {
        UserRepository userRepository = mock(UserRepository.class);

        User actor = new User();
        actor.setId(11L);
        actor.setEmail("admin@example.com");
        actor.setRole("system_admin");

        User target = new User();
        target.setId(42L);
        target.setEmail("retailer@example.com");
        target.setRole("retailer");
        target.setDisabled(false);
        target.setApprovalStatus(null);

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RetailerAdminService service = new RetailerAdminService(userRepository);

        User saved = service.approveRetailer(42L, "reviewed by admin", "admin@example.com");

        assertNotNull(saved);
        assertEquals("APPROVED", saved.getApprovalStatus().name());
        assertNotNull(saved.getApprovedAt());
        assertFalse(saved.isDisabled());
        assertEquals("reviewed by admin", saved.getAdminNote());
    }

    @Test
    void shouldRejectSelfStatusChangeForAdminUser() {
        UserRepository userRepository = mock(UserRepository.class);
        User actor = new User();
        actor.setId(7L);
        actor.setRole("system_admin");
        actor.setEmail("admin@example.com");

        RetailerAdminService service = new RetailerAdminService(userRepository);

        SecurityException ex = assertThrows(SecurityException.class,
                () -> service.enforceAdminGuard(actor, 7L, "BLOCKED", "No self-change"));

        assertTrue(ex.getMessage().contains("cannot change their own"));
    }

    @Test
    void shouldRequestMoreInfoForRetailerApprovalReview() {
        UserRepository userRepository = mock(UserRepository.class);

        User actor = new User();
        actor.setId(11L);
        actor.setEmail("admin@example.com");
        actor.setRole("system_admin");

        User target = new User();
        target.setId(42L);
        target.setEmail("retailer@example.com");
        target.setRole("retailer");
        target.setApprovalStatus(RetailerStatus.PENDING_APPROVAL);
        target.setDisabled(false);

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RetailerAdminService service = new RetailerAdminService(userRepository);

        User saved = service.requestMoreInfo(42L, "Please provide your GST certificate", "admin@example.com");

        assertNotNull(saved);
        assertEquals(RetailerStatus.MORE_INFO_REQUIRED, saved.getApprovalStatus());
        assertEquals("Please provide your GST certificate", saved.getAdminNote());
        assertTrue(saved.isDisabled());
    }

    @Test
    void shouldUpdateBothUserStatusFieldsForAdmin() {
        UserRepository userRepository = mock(UserRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);

        User actor = new User();
        actor.setId(11L);
        actor.setEmail("admin@example.com");
        actor.setRole("system_admin");

        User target = new User();
        target.setId(42L);
        target.setEmail("member@example.com");
        target.setRole("owner");
        target.setDisabled(true);
        target.setApprovalStatus(RetailerStatus.PENDING_APPROVAL);

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(userRepository.findById(42L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.com", "secret"));

        RetailerAdminController controller = new RetailerAdminController(
                new RetailerAdminService(userRepository), userRepository, auditLogRepository);
        ResponseEntity<Map<String, Object>> response = controller.updateUserStatus(42L, Map.of("status", "Active"));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().containsKey("success"));
        assertEquals("ACTIVE", response.getBody().get("status"));
        assertFalse(target.isDisabled());
        assertEquals(RetailerStatus.APPROVED, target.getApprovalStatus());

        ResponseEntity<Map<String, Object>> disabledResponse =
                controller.updateUserStatus(42L, Map.of("status", "DISABLED"));
        assertEquals(200, disabledResponse.getStatusCode().value());
        assertEquals("DISABLED", disabledResponse.getBody().get("status"));
        assertTrue(target.isDisabled());
        assertEquals(RetailerStatus.DISABLED, target.getApprovalStatus());
        verify(userRepository, org.mockito.Mockito.times(2)).save(target);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldKeepNewSignupInPendingApprovalState() {
        TenantRepository tenantRepository = mock(TenantRepository.class);
        PasswordSecurityService passwordSecurityService = mock(PasswordSecurityService.class);
        when(passwordSecurityService.hashNewPassword("StrongPassword123")).thenReturn("hashed-password");
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProvisioningService service = new UserProvisioningService(tenantRepository, passwordSecurityService);
        SignupRequest request = new SignupRequest();
        request.setBusinessName("Demo Store");
        request.setOwnerName("Demo Owner");
        request.setUsername("demostore");
        request.setEmail("demo@example.com");
        request.setPasswordHash("StrongPassword123");
        request.setRole("retailer");

        User saved = service.provisionUser(request, "demo@example.com", "demostore", Optional.empty());

        assertEquals(RetailerStatus.PENDING_APPROVAL, saved.getApprovalStatus());
        assertTrue(saved.isDisabled());
    }

    @Test
    void shouldReturnDashboardContractPayloadForSystemAdmin() {
        UserRepository userRepository = mock(UserRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        RetailerAdminService retailerAdminService = mock(RetailerAdminService.class);

        Tenant tenant = new Tenant();
        tenant.setId(10);

        User actor = new User();
        actor.setId(7L);
        actor.setEmail("admin@example.com");
        actor.setRole("system_admin");
        actor.setTenant(tenant);

        User retailer = new User();
        retailer.setId(42L);
        retailer.setBusinessName("Prime Retail");
        retailer.setOwnerName("Rohit Nair");
        retailer.setRole("retailer");
        retailer.setApprovalStatus(RetailerStatus.PENDING_APPROVAL);
        retailer.setTenant(tenant);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.com", "secret"));
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(actor));
        when(userRepository.findTenantUsersForManagement(isNull(), eq(true), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(retailer)));

        RetailerAdminController controller = new RetailerAdminController(retailerAdminService, userRepository, auditLogRepository);
        ResponseEntity<Map<String, Object>> response = controller.dashboard();

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().containsKey("data"));
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertNotNull(data);
        assertEquals(1L, data.get("totalRetailers"));
        assertEquals(1L, data.get("pendingApprovals"));
        assertTrue(data.containsKey("averageApprovalTime"));
        SecurityContextHolder.clearContext();
    }
}
