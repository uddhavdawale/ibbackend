package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Product;
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
        log.info("Fetching dashboard metrics");
        Map<String, Object> metrics = new HashMap<>();

        BigDecimal salesToday = invoiceRepo.getTodaySales();
        BigDecimal pendingPayments = invoiceRepo.getPendingAmount();
        long lowStock = productRepo.countLowStockItems();
        long ordersToday = invoiceRepo.countTodayInvoices();

        metrics.put("salesToday", salesToday != null ? salesToday.intValue() : 0);
        metrics.put("pendingPayments", pendingPayments != null ? pendingPayments.intValue() : 0);
        metrics.put("lowStock", lowStock);
        metrics.put("ordersToday", ordersToday);

        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();
        log.info("Fetching dashboard stats");

        try {
            stats.put("totalProducts", productRepo.count());
            stats.put("totalOrders", invoiceRepo.count());
            stats.put("lowStockItems", productRepo.countLowStockItems());
            stats.put("totalRevenue", invoiceRepo.getTotalRevenue());
            stats.put("salesToday", invoiceRepo.getTodaySales());
            stats.put("pendingAmount", 0);
            stats.put("pendingCount", 0);

            List<Product> productsWithExpiry = productRepo.findAllWithExpiry();
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
            stats.put("soonExpiryCount", productRepo.countExpiringSoon(thirtyDaysFromNow));
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("Error fetching dashboard stats", e);
            stats.put("error", e.getMessage());
            return ResponseEntity.ok(stats);
        }
    }
}
