package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.repository.WhatsAppIntegrationRepository.IntegrationSettings;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.WhatsAppIntegrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppIntegrationControllerTest {
    @Mock
    private UserRepository userRepository;

    @Mock
    private WhatsAppIntegrationService integrationService;

    @Mock
    private AuditLogService auditLogService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void returnsOnlyTenantConfigurationAndNeverTheSavedToken() {
        User storeAdmin = storeAdmin(14);
        authenticate(storeAdmin);
        TenantContext.setCurrentTenant(14L);
        when(integrationService.findForTenant(14)).thenReturn(Optional.of(
                new IntegrationSettings(14, "123456789", "987654321", "+919876543210", "ciphertext-secret", true)));
        WhatsAppIntegrationController controller = controller();

        var response = controller.getIntegration();

        assertThat(response.get("integration").toString())
                .contains("123456789", "+919876543210")
                .doesNotContain("ciphertext-secret");
        verify(integrationService).findForTenant(14);
    }

    @Test
    void deniesNonOwnersAndMismatchedTenantContext() {
        User manager = storeAdmin(14);
        manager.setRole("store_manager");
        authenticate(manager);
        TenantContext.setCurrentTenant(14L);

        assertThatThrownBy(() -> controller().getIntegration())
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(integrationService);

        manager.setRole("store_admin");
        TenantContext.setCurrentTenant(15L);
        assertThatThrownBy(() -> controller().getIntegration())
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(integrationService);
    }

    private WhatsAppIntegrationController controller() {
        return new WhatsAppIntegrationController(userRepository, integrationService, auditLogService, "");
    }

    private User storeAdmin(int tenantId) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User user = new User();
        user.setId(7L);
        user.setRole("store_admin");
        user.setEmail("owner@example.com");
        user.setTenant(tenant);
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        return user;
    }

    private void authenticate(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
    }
}
