package org.ib.ibbackend.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.ib.ibbackend.entity.AuditLog;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.AuditLogRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditControllerTest {

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void getAuditEvents_storeAdminAliasShouldReturnTenantScopedResults() {
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        AuditController controller = new AuditController(auditLogRepository, userRepository, auditLogService);

        User currentUser = buildUser(10L, "admin@example.com", "retailer", 7);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.com", null)
        );
        TenantContext.setCurrentTenant(7L);

        AuditLog log = new AuditLog();
        log.setId(900L);
        log.setUserId(10L);
        log.setTenantId(7L);
        log.setAction("LOGIN");
        log.setOutcome("SUCCESS");
        log.setOccurredAt(Instant.now());
        log.setRequestPath("/api/auth/login");
        log.setHttpMethod("POST");

        Page<AuditLog> page = new PageImpl<>(List.of(log));

        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(currentUser));
        when(auditLogRepository.findForAuditView(eq(7L), any(), any(), any(), any(), any())).thenReturn(page);
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(currentUser));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/audit/events");

        var response = controller.getAuditEvents(0, 25, null, null, null, null, null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("scopeTenantId", 7L);
        assertThat(response.getBody()).containsEntry("role", "store_admin");
        assertThat((List<?>) response.getBody().get("items")).hasSize(1);

        verify(auditLogService).success(eq("AUDIT_REPORT_VIEW"), eq("/api/audit/events"), anyString());
    }

    @Test
    void getAuditSummary_nonAuditRoleShouldBeDeniedAndLogged() {
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        AuditController controller = new AuditController(auditLogRepository, userRepository, auditLogService);

        User currentUser = buildUser(11L, "staff@example.com", "store_manager", 7);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("staff@example.com", null)
        );

        when(userRepository.findByEmail("staff@example.com")).thenReturn(Optional.of(currentUser));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/audit/summary");

        assertThrows(AccessDeniedException.class, () -> controller.getAuditSummary(30, null, request));
        verify(auditLogService).denied(eq("ACCESS_DENIED_EVENT"), eq("/api/audit/summary"), anyString());
    }

    @Test
    void exportAuditEvents_auditRelatedRoleShouldBeDenied() {
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        AuditLogService auditLogService = mock(AuditLogService.class);

        AuditController controller = new AuditController(auditLogRepository, userRepository, auditLogService);

        User currentUser = buildUser(12L, "audit@example.com", "audit_related", 5);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("audit@example.com", null)
        );
        TenantContext.setCurrentTenant(5L);

        when(userRepository.findByEmail("audit@example.com")).thenReturn(Optional.of(currentUser));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/audit/export");

        assertThrows(AccessDeniedException.class, () -> controller.exportAuditEvents(
                null,
                null,
                null,
                null,
                null,
                100,
                request
        ));
        verify(auditLogService).denied(eq("ACCESS_DENIED_EVENT"), eq("/api/audit/export"), anyString());
    }

    private User buildUser(Long id, String email, String role, int tenantId) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setCode("TEN" + tenantId);
        tenant.setName("Tenant " + tenantId);

        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setRole(role);
        user.setTenant(tenant);
        return user;
    }
}
