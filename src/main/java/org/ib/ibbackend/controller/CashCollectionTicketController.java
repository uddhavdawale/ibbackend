package org.ib.ibbackend.controller;

import jakarta.validation.Valid;
import org.ib.ibbackend.dto.CashCollectionTicketRequest;
import org.ib.ibbackend.dto.CashBalanceInitializationRequest;
import org.ib.ibbackend.dto.CashHandoverConfirmationRequest;
import org.ib.ibbackend.dto.CashHandoverResolutionRequest;
import org.ib.ibbackend.dto.CashOpeningBalanceRequest;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.service.CashBalanceService;
import org.ib.ibbackend.service.CashHandoverService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/cash-collection-tickets")
public class CashCollectionTicketController {
    private final CashHandoverService cashHandoverService;
    private final CashBalanceService cashBalanceService;

    public CashCollectionTicketController(
            CashHandoverService cashHandoverService, CashBalanceService cashBalanceService) {
        this.cashHandoverService = cashHandoverService;
        this.cashBalanceService = cashBalanceService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> submit(
            @Valid @RequestBody CashCollectionTicketRequest request) {
        requireTenantContext();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cashHandoverService.createCollectionTicket(request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> submitWithPaymentProof(
            @Valid @ModelAttribute CashCollectionTicketRequest request,
            @RequestParam(value = "paymentProof", required = false) MultipartFile paymentProof) {
        requireTenantContext();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cashHandoverService.createCollectionTicket(request, paymentProof));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        requireTenantContext();
        return ResponseEntity.ok(cashHandoverService.listCollectionTickets(status, page, size));
    }

    @GetMapping("/recipients")
    public ResponseEntity<Map<String, Object>> recipients() {
        requireTenantContext();
        return ResponseEntity.ok(cashBalanceService.listRecipients());
    }

    @GetMapping("/balances")
    public ResponseEntity<Map<String, Object>> balances(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        requireTenantContext();
        return ResponseEntity.ok(cashBalanceService.getBalances(page, size));
    }

    @PostMapping("/balances/initialize")
    public ResponseEntity<Map<String, Object>> initializeBalances(
            @Valid @RequestBody CashBalanceInitializationRequest request) {
        requireTenantContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(cashBalanceService.initialize(request));
    }

    @PostMapping("/balances/opening")
    public ResponseEntity<Map<String, Object>> recordOpeningBalance(
            @Valid @RequestBody CashOpeningBalanceRequest request) {
        requireTenantContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(cashBalanceService.recordOpeningBalance(request));
    }

    @PostMapping("/{ticketId}/count")
    public ResponseEntity<Map<String, Object>> count(
            @PathVariable Long ticketId,
            @Valid @RequestBody CashHandoverConfirmationRequest request) {
        requireTenantContext();
        return ResponseEntity.ok(cashHandoverService.countCollectionTicket(ticketId, request));
    }

    @PostMapping("/{ticketId}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @PathVariable Long ticketId,
            @Valid @RequestBody CashHandoverResolutionRequest request) {
        requireTenantContext();
        return ResponseEntity.ok(cashHandoverService.resolveCollectionTicket(ticketId, request));
    }

    @GetMapping("/{ticketId}/payment-proof")
    public ResponseEntity<byte[]> paymentProof(@PathVariable Long ticketId) {
        requireTenantContext();
        CashHandoverService.PaymentProof proof = cashHandoverService.getCollectionTicketPaymentProof(ticketId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(proof.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(proof.content());
    }

    private void requireTenantContext() {
        if (TenantContext.getCurrentTenant() == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
    }
}
