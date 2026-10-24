package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController()
@CrossOrigin(originPatterns  = "*")
//@CrossOrigin(origins = {  "http://localhost:5173","http://ibbackend:8080/api/" , "http://localhost:4173","https://ibfrontendlive.vercel.app/"})
@RequestMapping("/api/dashboard")
public class DashBoardAPI {
    private static final Logger log = LoggerFactory.getLogger(DashBoardAPI.class);

    @Autowired private ProductRepository productRepo;
    @Autowired
    private InvoiceRepository invoiceRepo;

    @GetMapping(value = {"", "/", "/summary"})
    public ResponseEntity<Map<String, Object>> getDashboardSummary() {
        return getDashboardMetrics();
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getDashboardMetrics() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        log.info("Fetching dashboard metrics for tenant={}", tenantId);
        Map<String, Object> metrics = new HashMap<>();

        BigDecimal salesToday = invoiceRepo.getTodaySalesByTenantId(tenantId);
        BigDecimal pendingPayments = invoiceRepo.getPendingAmountByTenantId(tenantId);
        long lowStock = productRepo.countLowStockItemsByTenantId(tenantId);
        long ordersToday = invoiceRepo.countTodayInvoicesByTenantId(tenantId);

        metrics.put("salesToday", salesToday != null ? salesToday.intValue() : 0);
        metrics.put("pendingPayments", pendingPayments != null ? pendingPayments.intValue() : 0);
        metrics.put("lowStock", lowStock);
        metrics.put("ordersToday", ordersToday);

        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        Map<String, Object> stats = new HashMap<>();
        log.info("Fetching dashboard stats for tenant={}", tenantId);

        try {
            stats.put("totalProducts", productRepo.countByTenantId(tenantId));
            stats.put("totalOrders", invoiceRepo.countByTenantId(tenantId));
            stats.put("lowStockItems", productRepo.countLowStockItemsByTenantId(tenantId));
            stats.put("totalRevenue", invoiceRepo.getTotalRevenueByTenantId(tenantId));
            stats.put("salesToday", invoiceRepo.getTodaySalesByTenantId(tenantId));
            stats.put("pendingAmount", invoiceRepo.getPendingAmountByTenantId(tenantId));
            stats.put("pendingCount", invoiceRepo.getPendingCountByTenantId(tenantId));

            List<Product> productsWithExpiry = productRepo.findAllWithExpiryByTenantId(tenantId);
            stats.put("products", productsWithExpiry);

            long soonExpiringCount = productsWithExpiry.stream()
                    .filter(p -> p.getExpiryDate() != null)
                    .filter(p -> {
                        LocalDate expiry = p.getExpiryDate();
                        return !expiry.isBefore(LocalDate.now()) &&
                                expiry.isBefore(LocalDate.now().plusDays(30));
                    })
                    .count();
            stats.put("soonExpiring", soonExpiringCount);

            LocalDate thirtyDaysFromNow = LocalDate.now().plusDays(30);
            stats.put("soonExpiryCount", productRepo.countExpiringSoonByTenantId(tenantId, thirtyDaysFromNow));
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("Error fetching dashboard stats", e);
            stats.put("error", e.getMessage());
            return ResponseEntity.ok(stats);
        }
    }
}
