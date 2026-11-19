package org.ib.ibbackend.controller;

import jakarta.validation.Valid;
import org.ib.ibbackend.dto.CashHandoverConfirmationRequest;
import org.ib.ibbackend.dto.CashHandoverRequest;
import org.ib.ibbackend.dto.CashHandoverResolutionRequest;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.service.CashHandoverService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cash-handovers")
public class CashHandoverController {
    private final CashHandoverService cashHandoverService;

    public CashHandoverController(CashHandoverService cashHandoverService) {
        this.cashHandoverService = cashHandoverService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody CashHandoverRequest request) {
        requireTenantContext();
        return ResponseEntity.status(HttpStatus.CREATED).body(cashHandoverService.create(request));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        requireTenantContext();
        return ResponseEntity.ok(cashHandoverService.list(status, page, size));
    }

    @GetMapping("/{handoverId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long handoverId) {
        requireTenantContext();
        return ResponseEntity.ok(cashHandoverService.get(handoverId));
    }

    @PostMapping("/{handoverId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable Long handoverId,
            @Valid @RequestBody CashHandoverConfirmationRequest request) {
        requireTenantContext();
        return ResponseEntity.ok(cashHandoverService.confirm(handoverId, request));
    }

    @PostMapping("/{handoverId}/resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @PathVariable Long handoverId,
            @Valid @RequestBody CashHandoverResolutionRequest request) {
        requireTenantContext();
        return ResponseEntity.ok(cashHandoverService.resolve(handoverId, request));
    }

    private void requireTenantContext() {
        if (TenantContext.getCurrentTenant() == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
    }
}
