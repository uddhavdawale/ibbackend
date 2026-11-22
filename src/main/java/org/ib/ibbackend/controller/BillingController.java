package org.ib.ibbackend.controller;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.HsnTaxRateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(originPatterns  = "*")
/*@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:*"})*/
@RequestMapping("/api/billing")
public class BillingController {

    private static final int MAX_BILLING_PRODUCTS_PAGE_SIZE = 200;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private HsnTaxRateService hsnTaxRateService;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    private void auditSuccess(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.success(action, resourceId, detail);
        }
    }

    // Reuse /api/products for billing (size=1000)
    @GetMapping("/products")
    public ResponseEntity<Page<Product>> getBillingProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 50 : Math.min(size, MAX_BILLING_PRODUCTS_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by("id").descending());
        auditSuccess("ADMIN_ACTION", "billing-products", "Billing products fetched");
        Page<Product> products = productRepository.findByTenantId(tenantId, pageable);
        Map<String, java.math.BigDecimal> rates = hsnTaxRateService.ratesForProducts(products.getContent());
        products.getContent().forEach(product -> {
            String hsn = HsnTaxRateService.normalizeCode(product.getHsnSac());
            product.setGstRate(rates.get(hsn));
            product.setGstRateStatus(hsnTaxRateService.rateStatus(hsn, rates));
        });
        return ResponseEntity.ok(products);
    }
}