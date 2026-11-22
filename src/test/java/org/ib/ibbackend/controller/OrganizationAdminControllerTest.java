package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Organization;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.dto.OrganizationRequest;
import org.ib.ibbackend.repository.OrganizationRepository;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrganizationAdminControllerTest {
    private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OrganizationAdminController controller = new OrganizationAdminController(
            organizationRepository, tenantRepository, userRepository, auditLogService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void storeAdminCannotCreateAnOrganization() {
        User storeAdmin = new User();
        storeAdmin.setRole("store_admin");
        when(userRepository.findByEmail("store@example.com")).thenReturn(Optional.of(storeAdmin));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("store@example.com", null));

        assertThrows(AccessDeniedException.class, () -> controller.createOrganization(
                organizationRequest("Retail Group")));

        verify(organizationRepository, never()).save(any(Organization.class));
    }

    @Test
    void systemAdminCanAssignStoreWithoutChangingItsTenantBoundary() {
        User systemAdmin = new User();
        systemAdmin.setRole("system_admin");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(systemAdmin));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.com", null));

        Organization organization = new Organization();
        organization.setId(5L);
        organization.setName("Retail Group");
        Tenant tenant = new Tenant();
        tenant.setId(9);
        tenant.setName("Store One");
        when(organizationRepository.findById(5L)).thenReturn(Optional.of(organization));
        when(tenantRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(tenant)).thenReturn(tenant);

        controller.assignStore(5L, 9L);

        org.junit.jupiter.api.Assertions.assertSame(organization, tenant.getOrganization());
        org.junit.jupiter.api.Assertions.assertEquals(9, tenant.getId());
        verify(tenantRepository).save(tenant);
    }

    @Test
    void systemAdminCanCreateOrganizationWithLegalContactDetails() {
        User systemAdmin = new User();
        systemAdmin.setRole("system_admin");
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(systemAdmin));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.com", null));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization saved = invocation.getArgument(0);
            saved.setId(12L);
            return saved;
        });

        OrganizationRequest request = organizationRequest("Retail Group");
        request.setAddress("Main Road");
        request.setCity("Pune");
        request.setState("Maharashtra");
        request.setPincode("411001");
        request.setGstin("27abcde1234f1z5");
        request.setPhone("0201234567");
        request.setEmail("billing@example.com");

        Map<String, Object> response = controller.createOrganization(request);

        assertEquals("27ABCDE1234F1Z5", response.get("gstin"));
        assertEquals("Maharashtra", response.get("state"));
        assertEquals("billing@example.com", response.get("email"));
    }

    private OrganizationRequest organizationRequest(String name) {
        OrganizationRequest request = new OrganizationRequest();
        request.setName(name);
        return request;
    }
}
