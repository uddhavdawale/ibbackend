package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CashReconciliationRequest;
import org.ib.ibbackend.dto.CollectPaymentRequest;
import org.ib.ibbackend.dto.RefundPaymentRequest;
import org.ib.ibbackend.entity.CashReconciliation;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.PaymentRecord;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CashReconciliationRepository;
import org.ib.ibbackend.repository.CashHandoverRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.ib.ibbackend.repository.PaymentRecordRepository;
import org.ib.ibbackend.repository.PaymentReportRow;
import org.ib.ibbackend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentCollectionServiceTest {
    private final PaymentRecordRepository paymentRepository = mock(PaymentRecordRepository.class);
    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CashReconciliationRepository reconciliationRepository = mock(CashReconciliationRepository.class);
    private final CashHandoverRepository handoverRepository = mock(CashHandoverRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final PaymentCollectionService service = new PaymentCollectionService(
            paymentRepository, invoiceRepository, nonGstInvoiceRepository, userRepository,
            reconciliationRepository, handoverRepository, auditLogService);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsPartialPaymentForAuthenticatedTenantCollector() {
        User collector = collector(7, "billing_manager");
        Invoice invoice = invoice(100, 20);
        authenticate(collector);
        when(invoiceRepository.findByIdAndTenantIdForUpdate(4L, 7L)).thenReturn(Optional.of(invoice));
        when(paymentRepository.findByTenant_IdAndIdempotencyKey(7, "request-1")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(PaymentRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.collectGstPayment(4L, request("30.00", "request-1"));

        assertEquals(new BigDecimal("50.00"), invoice.getPaidAmount());
        assertEquals(Invoice.InvoiceStatus.PENDING, invoice.getStatus());
        assertEquals(Invoice.PaymentStatus.PARTIAL, invoice.getPaymentStatus());
        assertEquals("CASH", result.get("method"));
        assertEquals("RCPT-", String.valueOf(result.get("receiptNo")).substring(0, 5));
        verify(invoiceRepository).save(invoice);
        verify(auditLogService).success(any(), any(), any());
    }

    @Test
    void sameIdempotencyKeyDoesNotCollectTwice() {
        User collector = collector(7, "store_admin");
        Invoice invoice = invoice(100, 20);
        authenticate(collector);
        PaymentRecord previous = new PaymentRecord();
        previous.setInvoice(invoice);
        previous.setAmount(new BigDecimal("30.00"));
        previous.setMethod("CASH");
        previous.setReceiptNo("RCPT-existing");
        previous.setIdempotencyKey("request-1");
        when(invoiceRepository.findByIdAndTenantIdForUpdate(4L, 7L)).thenReturn(Optional.of(invoice));
        when(paymentRepository.findByTenant_IdAndIdempotencyKey(7, "request-1")).thenReturn(Optional.of(previous));

        var result = service.collectGstPayment(4L, request("30.00", "request-1"));

        assertEquals("RCPT-existing", result.get("receiptNo"));
        assertEquals(new BigDecimal("20.00"), invoice.getPaidAmount());
        verify(paymentRepository, never()).save(any(PaymentRecord.class));
        verify(invoiceRepository, never()).save(invoice);
    }

    @Test
    void rejectsPaymentAboveLockedInvoiceBalance() {
        User collector = collector(7, "store_admin");
        Invoice invoice = invoice(100, 80);
        authenticate(collector);
        when(invoiceRepository.findByIdAndTenantIdForUpdate(4L, 7L)).thenReturn(Optional.of(invoice));
        when(paymentRepository.findByTenant_IdAndIdempotencyKey(7, "request-1")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.collectGstPayment(4L, request("30.00", "request-1")));
        verify(paymentRepository, never()).save(any(PaymentRecord.class));
        verify(invoiceRepository, never()).save(invoice);
    }

    @Test
    void rejectsCollectorFromDifferentTenant() {
        User collector = collector(8, "store_admin");
        TenantContext.setCurrentTenant(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(collector.getEmail(), null, List.of()));

        assertThrows(AccessDeniedException.class,
                () -> service.collectGstPayment(4L, request("30.00", "request-1")));
        verify(invoiceRepository, never()).findByIdAndTenantIdForUpdate(4L, 7L);
    }

    @Test
    void rejectsNegativeCashReconciliationValues() {
        User collector = collector(7, "store_admin");
        authenticate(collector);

        CashReconciliationRequest reconciliation = new CashReconciliationRequest();
        reconciliation.setReconciliationDate(java.time.LocalDate.now());
        reconciliation.setOpeningFloat(new BigDecimal("-1.00"));
        reconciliation.setPhysicalCashCounted(new BigDecimal("100.00"));

        assertThrows(ResponseStatusException.class, () -> service.reconcileCash(reconciliation));
        verify(reconciliationRepository, never()).save(any());
    }

    @Test
    void includesCompletedHandoversInCashReconciliation() {
        User collector = collector(7, "billing_manager");
        authenticate(collector);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        when(paymentRepository.sumCashByCollectorAndPeriod(
                org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.eq(22L),
                any(Instant.class), any(Instant.class))).thenReturn(new BigDecimal("100.00"));
        when(paymentRepository.sumCashRefundsByCollectorAndPeriod(
                org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.eq(22L),
                any(Instant.class), any(Instant.class))).thenReturn(new BigDecimal("5.00"));
        when(handoverRepository.sumIncomingCompletedForPeriod(
                org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.eq(22L),
                any(Instant.class), any(Instant.class))).thenReturn(new BigDecimal("10.00"));
        when(handoverRepository.sumOutgoingCompletedForPeriod(
                org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.eq(22L),
                any(Instant.class), any(Instant.class))).thenReturn(new BigDecimal("8.00"));
        when(reconciliationRepository.save(any(CashReconciliation.class))).thenAnswer(invocation -> {
            CashReconciliation record = invocation.getArgument(0);
            record.setId(5L);
            return record;
        });

        CashReconciliationRequest request = new CashReconciliationRequest();
        request.setReconciliationDate(today);
        request.setOpeningFloat(new BigDecimal("20.00"));
        request.setPhysicalCashCounted(new BigDecimal("117.00"));

        var result = service.reconcileCash(request);

        assertEquals(new BigDecimal("117.00"), result.get("expectedCash"));
        assertEquals(new BigDecimal("10.00"), result.get("incomingHandovers"));
        assertEquals(new BigDecimal("8.00"), result.get("outgoingHandovers"));
        assertEquals(new BigDecimal("0.00"), result.get("difference"));
    }

    @Test
    void recordsRefundAndRestoresInvoiceBalance() {
        User owner = collector(7, "store_admin");
        Invoice invoice = invoice(100, 50);
        PaymentRecord original = new PaymentRecord();
        original.setInvoice(invoice);
        original.setAmount(new BigDecimal("50.00"));
        original.setMethod("CASH");
        original.setStatus("RECEIVED");
        original.setSource("COLLECTION");
        original.setReceiptNo("RCPT-original");
        authenticate(owner);
        when(paymentRepository.findByTenant_IdAndIdempotencyKey(7, "refund-1")).thenReturn(Optional.empty());
        when(paymentRepository.findByIdAndTenantId(10L, 7)).thenReturn(Optional.of(original));
        when(invoiceRepository.findByIdAndTenantIdForUpdate(4L, 7L)).thenReturn(Optional.of(invoice));
        when(paymentRepository.findByIdAndTenantIdForUpdate(10L, 7)).thenReturn(Optional.of(original));
        when(paymentRepository.sumRefundsForPayment(10L, 7)).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.save(any(PaymentRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.refundPayment(10L, refundRequest("20.00", "refund-1"));

        assertEquals(new BigDecimal("30.00"), invoice.getPaidAmount());
        assertEquals(Invoice.InvoiceStatus.PENDING, invoice.getStatus());
        assertEquals(Invoice.PaymentStatus.PARTIAL, invoice.getPaymentStatus());
        assertEquals(new BigDecimal("-20.00"), result.get("amount"));
        assertEquals("REFUNDED", result.get("status"));
        verify(invoiceRepository).save(invoice);
        verify(auditLogService).success(any(), any(), any());
    }

    @Test
    void billingManagerCannotRefundPayments() {
        User manager = collector(7, "billing_manager");
        authenticate(manager);

        assertThrows(AccessDeniedException.class,
                () -> service.refundPayment(10L, refundRequest("20.00", "refund-1")));
        verify(paymentRepository, never()).save(any(PaymentRecord.class));
    }

    @Test
    void billingViewerReportIsRestrictedToAuthenticatedCollector() {
        User viewer = collector(7, "store_billing_viewer");
        authenticate(viewer);
        LocalDate reportDate = LocalDate.of(2026, 11, 17);
        when(paymentRepository.findReportRows(
                eq(7), any(Instant.class), any(Instant.class), eq(viewer.getId()),
                eq("CASH"), eq(99L), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        when(paymentRepository.summarize(
                eq(7), any(Instant.class), any(Instant.class), eq(viewer.getId()),
                eq("CASH"), eq(99L)))
                .thenReturn(new Object[]{BigDecimal.ZERO, 0L, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        when(invoiceRepository.getPendingAmountByTenantId(7L)).thenReturn(BigDecimal.ZERO);
        when(nonGstInvoiceRepository.getPendingAmountByTenantId(7L)).thenReturn(BigDecimal.ZERO);

        var result = service.getReport(reportDate, reportDate, 99L, "cash", 99L, 0, 50);

        assertEquals(reportDate.toString(), result.get("from"));
        assertEquals(reportDate.toString(), result.get("to"));
        verify(paymentRepository).findReportRows(
                eq(7), any(Instant.class), any(Instant.class), eq(viewer.getId()),
                eq("CASH"), eq(99L), any());
        verify(paymentRepository).summarize(
                eq(7), any(Instant.class), any(Instant.class), eq(viewer.getId()),
                eq("CASH"), eq(99L));
    }

    private User collector(int tenantId, String role) {
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User user = new User();
        user.setId(22L);
        user.setEmail("owner@example.com");
        user.setRole(role);
        user.setTenant(tenant);
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findByIdAndTenant_IdForUpdate(user.getId(), tenantId)).thenReturn(Optional.of(user));
        return user;
    }

    private Invoice invoice(int total, int paid) {
        Invoice invoice = new Invoice();
        invoice.setId(4L);
        invoice.setInvoiceNo("INV-4");
        invoice.setGrandTotal(new BigDecimal(total + ".00"));
        invoice.setPaidAmount(new BigDecimal(paid + ".00"));
        invoice.setStatus(Invoice.InvoiceStatus.PENDING);
        return invoice;
    }

    private void authenticate(User user) {
        TenantContext.setCurrentTenant((long) user.getTenant().getId());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
    }

    private CollectPaymentRequest request(String amount, String key) {
        CollectPaymentRequest request = new CollectPaymentRequest();
        request.setAmount(new BigDecimal(amount));
        request.setMethod("Cash");
        request.setIdempotencyKey(key);
        return request;
    }

    private RefundPaymentRequest refundRequest(String amount, String key) {
        RefundPaymentRequest request = new RefundPaymentRequest();
        request.setAmount(new BigDecimal(amount));
        request.setIdempotencyKey(key);
        return request;
    }
}
