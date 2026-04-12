package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController()
@CrossOrigin(origins = {  "http://localhost:5173","http://ibbackend:8080/api/" , "http://localhost:4173","https://ibfrontendlive.vercel.app/"})
@RequestMapping("/api/dashboard")
public class DashBoardAPI {
    @Autowired private ProductRepository productRepo;
    @Autowired
    private InvoiceRepository invoiceRepo;
  /*  @GetMapping("/api/dashboard")
    public DashboardResponse getOverview() { }

    @GetMapping("/api/inventory")
    public List<Product> getInventory() {

    } */
    /*
  @GetMapping("/metrics")
  public ResponseEntity<Map<String, Object>> getDashboardMetrics() {
      Map<String, Object> metrics = new HashMap<>();
      metrics.put("salesToday", salesService.getTodaySales());
      metrics.put("pendingPayments", paymentService.getPendingAmount());
      metrics.put("pendingCount", paymentService.getPendingCount());  // New
      metrics.put("lowStock", inventoryService.getLowStockCount());
      return ResponseEntity.ok(metrics);
  } */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getDashboardMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("salesToday", 200050);
        metrics.put("pendingAmount", 2546125);
        metrics.put("pendingCount", 8);  // New
        metrics.put("lowStock", "20");
        return ResponseEntity.ok(metrics);
    }
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();

        try {
            stats.put("totalProducts", productRepo.count());
            stats.put("totalOrders", invoiceRepo.count());
            stats.put("lowStockItems", productRepo.countLowStockItems());
            stats.put("totalRevenue", invoiceRepo.getTotalRevenue());
            stats.put("salesToday", invoiceRepo.getTodaySales());
            stats.put("pendingAmount", 0); // Default
            stats.put("pendingCount", 0);  // Default

            // ✅ Safe expiry check
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
            stats.put("error", e.getMessage());
            return ResponseEntity.ok(stats);
        }
    }
}
