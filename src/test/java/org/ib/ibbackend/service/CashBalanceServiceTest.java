package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CashBalanceInitializationRequest;
import org.ib.ibbackend.entity.RetailerStatus;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CashBalanceLedgerRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CashBalanceServiceTest {
    private final CashBalanceLedgerRepository ledgerRepository = mock(CashBalanceLedgerRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final CashBalanceService service =
            new CashBalanceService(ledgerRepository, userRepository, auditLogService);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsCashierOwnBalance() {
        User cashier = user(11L, 7, "billing_manager");
        authenticate(cashier);
        Instant trackingStart = Instant.parse("2026-11-01T00:00:00Z");
        Map<String, Object> balance = balance(11L, "Biller", "100.00");
        when(ledgerRepository.findTrackingStartedAt(7)).thenReturn(trackingStart);
        when(userRepository.findTenantCashCollectors(7)).thenReturn(List.of(cashier));
        when(ledgerRepository.findBalances(
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq(trackingStart),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of(balance));

        assertEquals(balance, service.getBalances(3, 10).get("balance"));
    }

    @Test
    void returnsTenantWideSummaryIndependentOfAdminPage() {
        User admin = user(20L, 7, "store_admin");
        User first = user(11L, 7, "billing_manager");
        User second = user(12L, 7, "store_manager");
        authenticate(admin);
        Instant trackingStart = Instant.parse("2026-11-01T00:00:00Z");
        when(ledgerRepository.findTrackingStartedAt(7)).thenReturn(trackingStart);
        when(userRepository.findTenantCashCollectors(7)).thenReturn(List.of(first, second));
        when(ledgerRepository.findBalances(
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq(trackingStart),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(balance(11L, "Biller A", "50.00"),
                        balance(12L, "Biller B", "100.00")));

        Map<String, Object> response = service.getBalances(0, 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) response.get("summary");
        assertEquals(1, content.size());
        assertEquals(12L, content.get(0).get("userId"));
        assertEquals(2, response.get("totalElements"));
        assertEquals(new BigDecimal("150.00"), summary.get("totalDueToSubmit"));
        assertEquals(new BigDecimal("150.00"), summary.get("totalAvailableToSubmit"));
    }

    @Test
    void automaticallyStartsCashierTrackingWithZeroOpeningDue() {
        User cashier = user(11L, 7, "billing_manager");
        authenticate(cashier);
        Instant trackingStart = Instant.parse("2026-11-01T00:00:00Z");
        when(ledgerRepository.findTrackingStartedAt(7)).thenReturn(null, trackingStart);
        when(ledgerRepository.ensureAutomaticTracking(7)).thenReturn(1);
        when(ledgerRepository.findBalances(
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq(trackingStart),
                org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(balance(11L, "Biller", "0.00")));

        @SuppressWarnings("unchecked")
        Map<String, Object> ownBalance =
                (Map<String, Object>) service.getBalances(0, 100).get("balance");
        assertEquals(new BigDecimal("0.00"), ownBalance.get("dueToSubmit"));
        verify(ledgerRepository).ensureAutomaticTracking(7);
    }

    @Test
    void initializesTrackingOnlyWithAnExplicitBalanceForEveryActiveCashier() {
        User admin = user(20L, 7, "store_admin");
        User cashier = user(11L, 7, "billing_manager");
        authenticate(admin);
        when(userRepository.findTenantCashCollectors(7)).thenReturn(List.of(cashier));
        when(ledgerRepository.findTrackingStartedAt(7)).thenReturn(null);
        when(ledgerRepository.initialize(
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.eq("tenant-init-1"))).thenReturn(1);
        when(ledgerRepository.insertOpeningBalance(
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("0.00")),
                org.mockito.ArgumentMatchers.eq("Reconciled at rollout"),
                org.mockito.ArgumentMatchers.eq("opening-11"))).thenReturn(1);

        CashBalanceInitializationRequest request = new CashBalanceInitializationRequest();
        request.setTrackingStartedAt(Instant.now().minusSeconds(60));
        request.setIdempotencyKey("tenant-init-1");
        CashBalanceInitializationRequest.OpeningBalance opening =
                new CashBalanceInitializationRequest.OpeningBalance();
        opening.setUserId(11L);
        opening.setAmount(new BigDecimal("0.00"));
        opening.setNote("Reconciled at rollout");
        opening.setIdempotencyKey("opening-11");
        request.setOpeningBalances(List.of(opening));

        assertTrue((Boolean) service.initialize(request).get("initialized"));
        verify(auditLogService).success(
                "CASH_BALANCE_TRACKING_INITIALIZED", "7",
                "Cash tracking initialized with audited opening balances");
    }

    private Map<String, Object> balance(long id, String name, String due) {
        return Map.of(
                "userId", id,
                "userName", name,
                "dueToSubmit", new BigDecimal(due),
                "pendingHandoverAmount", new BigDecimal("0.00"),
                "disputedHandoverAmount", new BigDecimal("0.00"),
                "availableToSubmit", new BigDecimal(due),
                "asOf", Instant.parse("2026-11-17T17:00:00Z"));
    }

    private User user(long id, int tenantId, String role) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User user = new User();
        user.setId(id);
        user.setTenant(tenant);
        user.setRole(role);
        user.setEmail("user" + id + "@example.com");
        user.setOwnerName("User " + id);
        user.setApprovalStatus(RetailerStatus.APPROVED);
        user.setDisabled(false);
        return user;
    }

    private void authenticate(User user) {
        TenantContext.setCurrentTenant((long) user.getTenant().getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }
}
