package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.ReturnOrderRequest;
import org.ib.ibbackend.entity.ReturnOrder;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.ReturnOrderRepository;
import org.ib.ibbackend.service.ReturnOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/api/returns")
public class ReturnOrderController {

    private final ReturnOrderService returnOrderService;
    private final ReturnOrderRepository returnOrderRepository;

    @Autowired
    public ReturnOrderController(ReturnOrderService returnOrderService,
                                ReturnOrderRepository returnOrderRepository) {
        this.returnOrderService = returnOrderService;
        this.returnOrderRepository = returnOrderRepository;
    }

    @PostMapping
    public ResponseEntity<ReturnOrder> createReturnOrder(@RequestBody ReturnOrderRequest request) {
        return ResponseEntity.ok(returnOrderService.createReturnOrder(request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    @GetMapping
    public ResponseEntity<List<ReturnOrder>> getReturnOrders() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        return ResponseEntity.ok(returnOrderRepository.findByTenantId(tenantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReturnOrder> getReturnOrder(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        return returnOrderRepository.findByIdAndTenantId(id, tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
