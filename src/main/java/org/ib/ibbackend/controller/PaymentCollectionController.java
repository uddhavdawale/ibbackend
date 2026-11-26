package org.ib.ibbackend.controller;

import jakarta.validation.Valid;
import org.ib.ibbackend.dto.CashReconciliationRequest;
import org.ib.ibbackend.dto.CollectPaymentRequest;
import org.ib.ibbackend.dto.RefundPaymentRequest;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.service.PaymentCollectionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PaymentCollectionController {
    private static final int MAX_REPORT_DAYS = 366;
    private static final ZoneId RETAILER_ZONE = ZoneId.of("Asia/Kolkata");
    private final PaymentCollectionService paymentCollectionService;

    public PaymentCollectionController(PaymentCollectionService paymentCollectionService) {
        this.paymentCollectionService = paymentCollectionService;
    }

    @PostMapping("/invoices/{invoiceId}/payments")
    public ResponseEntity<Map<String, Object>> collectInvoicePayment(
            @PathVariable Long invoiceId,
            @Valid @RequestBody CollectPaymentRequest request) {
        requireTenantContext();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentCollectionService.collectGstPayment(invoiceId, request));
    }

    @PostMapping("/non-gst-invoices/{invoiceId}/payments")
    public ResponseEntity<Map<String, Object>> collectNonGstInvoicePayment(
            @PathVariable Long invoiceId,
            @Valid @RequestBody CollectPaymentRequest request) {
        requireTenantContext();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentCollectionService.collectNonGstPayment(invoiceId, request));
    }

    @PostMapping("/payments/{paymentId}/refunds")
    public ResponseEntity<Map<String, Object>> refundPayment(
            @PathVariable Long paymentId,
            @Valid @RequestBody RefundPaymentRequest request) {
        requireTenantContext();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentCollectionService.refundPayment(paymentId, request));
    }

    @GetMapping("/payments/refundable")
    public ResponseEntity<List<Map<String, Object>>> getRefundablePaymentsForCancelledInvoice(
            @RequestParam Long invoiceId,
            @RequestParam String billType) {
        requireTenantContext();
        return ResponseEntity.ok(
                paymentCollectionService.getRefundablePaymentsForCancelledInvoice(invoiceId, billType));
    }

    @GetMapping("/payments")
    public ResponseEntity<Map<String, Object>> getCollectionReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long collectorId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        requireTenantContext();
        LocalDate end = to == null ? LocalDate.now(RETAILER_ZONE) : to;
        LocalDate start = from == null ? end : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > MAX_REPORT_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Date range must be valid and no longer than 366 days");
        }
        return ResponseEntity.ok(paymentCollectionService.getReport(
                start, end, collectorId, method, invoiceId, page, size));
    }

    @PostMapping("/cash-reconciliations")
    public ResponseEntity<Map<String, Object>> reconcileCash(
            @Valid @RequestBody CashReconciliationRequest request) {
        requireTenantContext();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentCollectionService.reconcileCash(request));
    }

    @GetMapping("/cash-reconciliations")
    public ResponseEntity<Map<String, Object>> getReconciliations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        requireTenantContext();
        return ResponseEntity.ok(paymentCollectionService.getReconciliations(page, size));
    }

    private void requireTenantContext() {
        if (TenantContext.getCurrentTenant() == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
    }
}
