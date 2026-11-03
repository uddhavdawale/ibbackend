package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.ExchangeOrderRequest;
import org.ib.ibbackend.entity.ExchangeOrder;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.ExchangeOrderRepository;
import org.ib.ibbackend.service.ExchangeOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin(originPatterns = "*")
@RequestMapping("/api/exchanges")
public class ExchangeOrderController {

    private final ExchangeOrderService exchangeOrderService;
    private final ExchangeOrderRepository exchangeOrderRepository;

    @Autowired
    public ExchangeOrderController(ExchangeOrderService exchangeOrderService,
                                  ExchangeOrderRepository exchangeOrderRepository) {
        this.exchangeOrderService = exchangeOrderService;
        this.exchangeOrderRepository = exchangeOrderRepository;
    }

    @PostMapping
    public ResponseEntity<ExchangeOrder> createExchangeOrder(@RequestBody ExchangeOrderRequest request) {
        return ResponseEntity.ok(exchangeOrderService.createExchangeOrder(request));
    }

    @GetMapping
    public ResponseEntity<List<ExchangeOrder>> getExchangeOrders() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        return ResponseEntity.ok(exchangeOrderRepository.findByTenantId(tenantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExchangeOrder> getExchangeOrder(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        return exchangeOrderRepository.findByIdAndTenantId(id, tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
