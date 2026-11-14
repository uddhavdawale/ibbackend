package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InvoiceItemRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceItemRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

@RestController()
@CrossOrigin(originPatterns  = "*")
//@CrossOrigin(origins = {  "http://localhost:5173","http://ibbackend:8080/api/" , "http://localhost:4173","https://ibfrontendlive.vercel.app/"})
@RequestMapping("/api/dashboard")
public class DashBoardAPI {
    private static final Logger log = LoggerFactory.getLogger(DashBoardAPI.class);
    private static final int TOP_PRODUCTS_LIMIT = 10;
    private static final int MAX_PRODUCTS_IN_STATS = 200;

    @Autowired private ProductRepository productRepo;
    @Autowired
    private InvoiceRepository invoiceRepo;
    @Autowired
    private NonGstInvoiceRepository nonGstInvoiceRepo;
    @Autowired
    private UserRepository userRepository;
    @Autowired(required = false)
    private InvoiceItemRepository invoiceItemRepo;
    @Autowired(required = false)
    private NonGstInvoiceItemRepository nonGstInvoiceItemRepo;
    @Autowired(required = false)
    private AuditLogService auditLogService;

    private void auditSuccess(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.success(action, resourceId, detail);
        }
    }

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

        BigDecimal salesToday = sumAmounts(
                invoiceRepo.getTodaySalesByTenantId(tenantId),
                nonGstInvoiceRepo.getTodaySalesByTenantId(tenantId)
        );
        BigDecimal pendingPayments = sumAmounts(
                invoiceRepo.getPendingAmountByTenantId(tenantId),
                nonGstInvoiceRepo.getPendingAmountByTenantId(tenantId)
        );
        long lowStock = productRepo.countLowStockItemsByTenantId(tenantId);
        long ordersToday = invoiceRepo.countTodayInvoicesByTenantId(tenantId)
                + nonGstInvoiceRepo.countTodayInvoicesByTenantId(tenantId);
        metrics.put("salesToday", salesToday.setScale(2, java.math.RoundingMode.HALF_UP));
        metrics.put("pendingPayments", pendingPayments != null ? pendingPayments.setScale(2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
        metrics.put("lowStock", lowStock);
        metrics.put("ordersToday", ordersToday);
        metrics.put("topSoldProducts", getTopSoldProducts(tenantId, TOP_PRODUCTS_LIMIT));
        enrichSecurityContext(metrics);
        auditSuccess("ADMIN_ACTION", "dashboard-metrics", "Dashboard metrics fetched");

        return secureNoStoreResponse(metrics);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        User currentUser = resolveCurrentUser();
        String role = currentUser != null && currentUser.getRole() != null
                ? currentUser.getRole().toUpperCase()
                : "USER";

        Map<String, Object> stats = new HashMap<>();
        log.info("Fetching dashboard stats for tenant={}", tenantId);

        try {
            stats.put("totalProducts", productRepo.countByTenantId(tenantId));
            stats.put("totalOrders", invoiceRepo.countByTenantId(tenantId)
                    + nonGstInvoiceRepo.countByTenantId(tenantId));
            stats.put("lowStockItems", productRepo.countLowStockItemsByTenantId(tenantId));
            stats.put("totalRevenue", sumAmounts(
                    invoiceRepo.getTotalRevenueByTenantId(tenantId),
                    nonGstInvoiceRepo.getTotalRevenueByTenantId(tenantId)
            ));
            stats.put("salesToday", sumAmounts(
                    invoiceRepo.getTodaySalesByTenantId(tenantId),
                    nonGstInvoiceRepo.getTodaySalesByTenantId(tenantId)
            ));
            stats.put("pendingAmount", sumAmounts(
                    invoiceRepo.getPendingAmountByTenantId(tenantId),
                    nonGstInvoiceRepo.getPendingAmountByTenantId(tenantId)
            ));
            stats.put("pendingCount", invoiceRepo.getPendingCountByTenantId(tenantId)
                    + nonGstInvoiceRepo.getPendingCountByTenantId(tenantId));
            stats.put("topSoldProducts", getTopSoldProducts(tenantId, TOP_PRODUCTS_LIMIT));

            List<Product> productsWithExpiry = productRepo.findAllWithExpiryByTenantId(tenantId);
            if (productsWithExpiry.size() > MAX_PRODUCTS_IN_STATS) {
                productsWithExpiry = productsWithExpiry.subList(0, MAX_PRODUCTS_IN_STATS);
            }
            if ("STORE_ADMIN".equals(role)) {
                stats.put("products", productsWithExpiry);
            } else {
                stats.put("products", List.of());
            }

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
            enrichSecurityContext(stats);
            auditSuccess("ADMIN_ACTION", "dashboard-stats", "Dashboard stats fetched");
            return secureNoStoreResponse(stats);

        } catch (Exception e) {
            log.error("Error fetching dashboard stats", e);
            stats.put("error", e.getMessage());
            return secureNoStoreResponse(stats);
        }
    }

    private void enrichSecurityContext(Map<String, Object> payload) {
        User user = resolveCurrentUser();
        if (user != null) {
            payload.put("tenantId", user.getTenant() != null ? user.getTenant().getId() : null);
            payload.put("userId", user.getId());
            payload.put("role", user.getRole());
        }
    }

    private User resolveCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : null;
        if (email == null || email.isBlank()) {
            return null;
        }
        return userRepository.findByEmail(email).orElse(null);
    }

    private <T> ResponseEntity<T> secureNoStoreResponse(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate().cachePrivate().sMaxAge(0, TimeUnit.SECONDS))
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .body(body);
    }

    private BigDecimal sumAmounts(BigDecimal... amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            if (amount != null) {
                total = total.add(amount);
            }
        }
        return total;
    }

    private List<Map<String, Object>> getTopSoldProducts(Long tenantId, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        PageRequest topLimit = PageRequest.of(0, limit);

        if (invoiceItemRepo != null) {
            List<Object[]> gstResults = invoiceItemRepo.findTopSoldProductsByTenantId(
                    tenantId,
                    Invoice.InvoiceStatus.CANCELLED,
                    topLimit
            );
            mergeTopSoldRows(merged, gstResults);
        }

        if (nonGstInvoiceItemRepo != null) {
            List<Object[]> nonGstResults = nonGstInvoiceItemRepo.findTopSoldProductsByTenantId(
                    tenantId,
                    "CANCELLED",
                    topLimit
            );
            mergeTopSoldRows(merged, nonGstResults);
        }

        List<Map<String, Object>> ranked = new ArrayList<>(merged.values());
        ranked.sort(
                Comparator
                        .comparing((Map<String, Object> item) -> ((Number) item.get("totalQty")).longValue())
                        .reversed()
                        .thenComparing(item -> String.valueOf(item.get("name")), String.CASE_INSENSITIVE_ORDER)
        );

        if (ranked.size() > limit) {
            return ranked.subList(0, limit);
        }
        return ranked;
    }

    private void mergeTopSoldRows(Map<String, Map<String, Object>> merged, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        for (Object[] row : rows) {
            Long productId = toLong(row[0]);
            String name = row[1] != null ? String.valueOf(row[1]) : "Unknown Product";
            String sku = row[2] != null ? String.valueOf(row[2]) : "";
            long qty = toLong(row[3]) == null ? 0L : toLong(row[3]);
            BigDecimal amount = row[4] instanceof BigDecimal ? (BigDecimal) row[4] : BigDecimal.ZERO;

            String key = productId != null ? "ID:" + productId : "NS:" + name + "|" + sku;
            Map<String, Object> existing = merged.get(key);

            if (existing == null) {
                Map<String, Object> item = new HashMap<>();
                item.put("productId", productId);
                item.put("name", name);
                item.put("sku", sku);
                item.put("totalQty", qty);
                item.put("totalAmount", amount);
                merged.put(key, item);
            } else {
                long currentQty = ((Number) existing.get("totalQty")).longValue();
                BigDecimal currentAmount = existing.get("totalAmount") instanceof BigDecimal
                        ? (BigDecimal) existing.get("totalAmount")
                        : BigDecimal.ZERO;
                existing.put("totalQty", currentQty + qty);
                existing.put("totalAmount", currentAmount.add(amount));
            }
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
