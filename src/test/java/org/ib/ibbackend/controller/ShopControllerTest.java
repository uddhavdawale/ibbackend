package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.TenantAssetType;
import org.ib.ibbackend.entity.Organization;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.TenantRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.TenantAssetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopControllerTest {
    private final UserRepository userRepository = mock(UserRepository.class);
    private final TenantRepository tenantRepository = mock(TenantRepository.class);
    private final TenantAssetService tenantAssetService = mock(TenantAssetService.class);
    private final ShopController controller =
            new ShopController(userRepository, tenantRepository, tenantAssetService);

    @AfterEach
    void clearRequestContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAllowRetailerToUpdateOptionalShopAddressWithoutUploadingFiles() {
        Tenant tenant = new Tenant();
        tenant.setId(7);
        tenant.setName("Demo Store");

        User user = new User();
        user.setId(12L);
        user.setEmail("owner@example.com");
        user.setBusinessName("Demo Store");
        user.setRole("store_admin");
        user.setTenant(tenant);

        authenticate();
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tenantAssetService.availableTypes(tenant)).thenReturn(Set.of());

        controller.updateCurrentShop(
                "Main Road", "Pune", "411001", null, null, null, null, null, null, null, null);

        assertEquals("Main Road", tenant.getAddress());
        assertEquals("Main Road", user.getAddress());
        assertEquals("Pune", tenant.getCity());
        assertEquals("411001", tenant.getPincode());
        verify(tenantRepository).save(tenant);
        verify(userRepository).save(user);
        verify(tenantAssetService).storeIfProvided(tenant, TenantAssetType.LOGO, null);
    }

    @Test
    void shouldNotWriteProfileRowsWhenOnlyOptionalFilesAreOmitted() {
        Tenant tenant = new Tenant();
        tenant.setId(7);
        User user = new User();
        user.setEmail("owner@example.com");
        user.setRole("store_admin");
        user.setTenant(tenant);
        authenticate();
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(tenantAssetService.availableTypes(tenant)).thenReturn(Set.of());

        controller.updateCurrentShop(null, null, null, null, null, null, null, null, null, null, null);

        verify(tenantRepository, never()).save(any(Tenant.class));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void shouldDenyShopDocumentOperationsToNonRetailerTenantUsers() {
        User user = new User();
        user.setEmail("manager@example.com");
        user.setRole("store_manager");
        user.setTenant(new Tenant());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager@example.com", null, Set.of()));
        when(userRepository.findByEmail("manager@example.com")).thenReturn(Optional.of(user));

        assertThrows(AccessDeniedException.class,
                () -> controller.updateCurrentShop(
                        null, null, null, null, null, null, null, null, null, null, null));
        verify(tenantAssetService, never()).storeIfProvided(any(), any(), any(MultipartFile.class));
    }

    @Test
    void shouldPersistStoreLegalDetailsSeparatelyFromTheLoginEmail() {
        Tenant tenant = new Tenant();
        tenant.setId(7);
        User user = new User();
        user.setEmail("owner-login@example.com");
        user.setRole("store_admin");
        user.setTenant(tenant);
        authenticate();
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tenantAssetService.availableTypes(tenant)).thenReturn(Set.of());

        Map<?, ?> responseShop = (Map<?, ?>) controller.updateCurrentShop(
                null, null, null, "Maharashtra", "27abcde1234f1z5",
                "0201234567", "billing@example.com", null, null, null, null)
                .getBody().get("shop");

        assertEquals("Maharashtra", tenant.getState());
        assertEquals("27ABCDE1234F1Z5", tenant.getGstin());
        assertEquals("0201234567", tenant.getPhone());
        assertEquals("billing@example.com", tenant.getEmail());
        assertEquals("owner-login@example.com", user.getEmail());
        assertEquals(true, responseShop.get("canCreateGstInvoice"));
    }

    @Test
    void shouldReturnOnlyTheAuthenticatedStoresOrganizationLegalSummary() {
        Tenant tenant = new Tenant();
        tenant.setId(7);
        tenant.setName("Demo Store");
        Organization organization = new Organization();
        organization.setId(42L);
        organization.setName("Retail Group");
        organization.setGstin("27ABCDE1234F1Z5");
        organization.setAddress("Main Road");
        organization.setCity("Pune");
        organization.setState("Maharashtra");
        organization.setPincode("411001");
        tenant.setOrganization(organization);

        User user = new User();
        user.setId(12L);
        user.setEmail("owner@example.com");
        user.setBusinessName("Demo Store");
        user.setRole("store_admin");
        user.setTenant(tenant);
        authenticate();
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(tenantAssetService.availableTypes(tenant)).thenReturn(Set.of());

        Map<?, ?> shop = (Map<?, ?>) controller.getCurrentShop().getBody().get("shop");
        Map<?, ?> orgSummary = (Map<?, ?>) shop.get("organization");

        assertEquals(42L, orgSummary.get("id"));
        assertEquals("Retail Group", orgSummary.get("name"));
        assertEquals("27ABCDE1234F1Z5", orgSummary.get("gstin"));
        assertEquals("Main Road, Pune, Maharashtra, 411001", orgSummary.get("registeredAddress"));
        assertEquals(false, shop.get("canCreateGstInvoice"));
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner@example.com", null, Set.of()));
        TenantContext.setCurrentTenant(7L);
    }
}
