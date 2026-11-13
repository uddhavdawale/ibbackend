package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.RefreshToken;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.RefreshTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentUserCompatibility_shouldReturnSamePayloadAsMeEndpoint() {
        UserRepository userRepository = mock(UserRepository.class);
        UserController controller = new UserController(userRepository);

        User user = new User();
        user.setId(7L);
        user.setEmail("owner@example.com");
        user.setBusinessName("Retail Store");
        user.setOwnerName("Store Owner");
        user.setRole("store_admin");
        Tenant tenant = new Tenant();
        tenant.setId(21);
        user.setTenant(tenant);

        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner@example.com", null)
        );

        ResponseEntity<Map<String, Object>> response = controller.getCurrentUserCompatibility();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));

        Map<String, Object> payload = (Map<String, Object>) response.getBody().get("user");
        assertNotNull(payload);
        assertEquals(7L, payload.get("id"));
        assertEquals("owner@example.com", payload.get("email"));
        assertEquals(21, payload.get("tenantId"));
        verify(userRepository).findByEmail("owner@example.com");
    }

    @Test
    void getCurrentUserCompatibility_shouldRejectAnonymousContext() {
        UserRepository userRepository = mock(UserRepository.class);
        UserController controller = new UserController(userRepository);

        assertThrows(AccessDeniedException.class, controller::getCurrentUserCompatibility);
    }

    @Test
    void getExistingUsers_shouldReturnOnlyTenantScopedRecords() {
        UserRepository userRepository = mock(UserRepository.class);
        UserController controller = new UserController(userRepository);

        User admin = new User();
        admin.setId(1L);
        admin.setEmail("admin@example.com");
        admin.setRole("owner");
        Tenant adminTenant = new Tenant();
        adminTenant.setId(77);
        admin.setTenant(adminTenant);

        User child = new User();
        child.setId(2L);
        child.setUsername("staff77");
        child.setEmail("staff77@example.com");
        child.setOwnerName("Staff User");
        child.setRole("store_manager");
        child.setTenant(adminTenant);

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(userRepository.findTenantUsersForManagement(eq("staff"), eq(false), any()))
            .thenReturn(new PageImpl<>(List.of(child), PageRequest.of(0, 20), 1));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.com", null)
        );

        ResponseEntity<Map<String, Object>> response = controller.getExistingUsers(0, 20, "staff", false);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals(77, response.getBody().get("tenantId"));
        assertEquals(1L, response.getBody().get("totalItems"));

        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertNotNull(items);
        assertEquals(1, items.size());
        assertEquals(2L, items.get(0).get("id"));
        assertEquals(false, items.get(0).get("disabled"));
        assertEquals("ACTIVE", items.get(0).get("status"));
    }

    @Test
    void getExistingUsers_shouldReturnAllPlatformUsersForSystemAdmin() {
        UserRepository userRepository = mock(UserRepository.class);
        UserController controller = new UserController(userRepository);

        User systemAdmin = new User();
        systemAdmin.setId(99L);
        systemAdmin.setEmail("platform-admin@example.com");
        systemAdmin.setRole("system_admin");

        User platformUser = new User();
        platformUser.setId(101L);
        platformUser.setUsername("tenant-owner");
        platformUser.setEmail("owner@example.com");
        platformUser.setOwnerName("Platform Owner");
        platformUser.setRole("owner");
        Tenant tenant = new Tenant();
        tenant.setId(12);
        platformUser.setTenant(tenant);

        when(userRepository.findByEmail("platform-admin@example.com")).thenReturn(Optional.of(systemAdmin));
        when(userRepository.findAllUsersForManagement(eq("owner"), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of(platformUser), PageRequest.of(0, 20), 1));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("platform-admin@example.com", null)
        );

        ResponseEntity<Map<String, Object>> response = controller.getExistingUsers(0, 20, "owner", false);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals(1L, response.getBody().get("totalItems"));
        assertEquals(null, response.getBody().get("tenantId"));

        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertNotNull(items);
        assertEquals(1, items.size());
        assertEquals(101L, items.get(0).get("id"));
    }

    @Test
    void disableUser_shouldSetDisabledRoleAndRevokeTokens() {
        UserRepository userRepository = mock(UserRepository.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        UserController controller = new UserController(userRepository, refreshTokenService, null, null);

        Tenant tenant = new Tenant();
        tenant.setId(88);

        User admin = new User();
        admin.setId(10L);
        admin.setEmail("admin88@example.com");
        admin.setRole("system_admin");
        admin.setTenant(tenant);

        User target = new User();
        target.setId(11L);
        target.setEmail("target88@example.com");
        target.setRole("store_manager");
        target.setTenant(tenant);

        when(userRepository.findByEmail("admin88@example.com")).thenReturn(Optional.of(admin));
        when(userRepository.findById(11L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin88@example.com", null)
        );

        ResponseEntity<Map<String, Object>> response = controller.disableUser(11L);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        assertTrue(target.isDisabled());
        verify(refreshTokenService).revokeAllActiveTokensForUser(target);
    }

    @Test
    void disableUser_shouldRejectSelfDisable() {
        UserRepository userRepository = mock(UserRepository.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        UserController controller = new UserController(userRepository, refreshTokenService, null, null);

        Tenant tenant = new Tenant();
        tenant.setId(89);

        User admin = new User();
        admin.setId(12L);
        admin.setEmail("admin89@example.com");
        admin.setRole("system_admin");
        admin.setTenant(tenant);

        when(userRepository.findByEmail("admin89@example.com")).thenReturn(Optional.of(admin));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin89@example.com", null)
        );

        ResponseEntity<Map<String, Object>> response = controller.disableUser(12L);

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse((Boolean) response.getBody().get("success"));
        verify(userRepository, never()).findByIdAndTenant_Id(any(), anyInt());
        verify(refreshTokenService, never()).revokeAllActiveTokensForUser(any(User.class));
    }

    @Test
    void deleteUser_shouldSoftDeleteAndRevokeTokens() {
        UserRepository userRepository = mock(UserRepository.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        UserController controller = new UserController(userRepository, refreshTokenService, null, null);

        Tenant tenant = new Tenant();
        tenant.setId(90);

        User admin = new User();
        admin.setId(20L);
        admin.setEmail("admin90@example.com");
        admin.setRole("system_admin");
        admin.setTenant(tenant);

        User target = new User();
        target.setId(21L);
        target.setEmail("target90@example.com");
        target.setRole("retailer");
        target.setTenant(tenant);

        when(userRepository.findByEmail("admin90@example.com")).thenReturn(Optional.of(admin));
        when(userRepository.findById(21L)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin90@example.com", null)
        );

        ResponseEntity<Map<String, Object>> response = controller.deleteUser(21L);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertTrue((Boolean) response.getBody().get("success"));
        assertNotNull(target.getDeletedAt());
        assertEquals(20L, target.getDeletedBy());
        assertTrue(target.isDisabled());

        verify(userRepository, atLeastOnce()).save(argThat(saved ->
                saved.getId().equals(21L)
                        && saved.getDeletedAt() != null
                        && Long.valueOf(20L).equals(saved.getDeletedBy())
                && saved.isDisabled()
        ));
        verify(refreshTokenService).revokeAllActiveTokensForUser(target);
    }

    @Test
    void deleteUser_shouldRejectSelfDelete() {
        UserRepository userRepository = mock(UserRepository.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        UserController controller = new UserController(userRepository, refreshTokenService, null, null);

        Tenant tenant = new Tenant();
        tenant.setId(91);

        User admin = new User();
        admin.setId(22L);
        admin.setEmail("admin91@example.com");
        admin.setRole("system_admin");
        admin.setTenant(tenant);

        when(userRepository.findByEmail("admin91@example.com")).thenReturn(Optional.of(admin));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin91@example.com", null)
        );

        ResponseEntity<Map<String, Object>> response = controller.deleteUser(22L);

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertFalse((Boolean) response.getBody().get("success"));
        verify(userRepository, never()).findByIdAndTenant_Id(any(), anyInt());
        verify(userRepository, never()).save(any(User.class));
        verify(refreshTokenService, never()).revokeAllActiveTokensForUser(any(User.class));
    }
}
