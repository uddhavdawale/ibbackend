package org.ib.ibbackend.controller;


import org.ib.ibbackend.dto.ProductUpsertRequest;
import org.ib.ibbackend.dto.ProductResponse;
import org.ib.ibbackend.entity.InventoryMovement;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.HsnTaxRateService;
import org.ib.ibbackend.service.InventorySecurityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

@RestController
@CrossOrigin(originPatterns  = "*")
//@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:8080/"})
@RequestMapping("/api/products")
public class ProductController {

    private static final int MAX_PRODUCTS_PAGE_SIZE = 200;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventorySecurityService inventorySecurityService;

    @Autowired
    private HsnTaxRateService hsnTaxRateService;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    private void auditSuccess(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.success(action, resourceId, detail);
        }
    }

    @GetMapping
    public ResponseEntity<Page<ProductResponse>> getProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir
    ) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 50 : Math.min(size, MAX_PRODUCTS_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, sort);

        Page<Product> products;

        if (!search.isBlank()) {
            products = productRepository.findBySearchAndTenantId(tenantId, search, pageable);
        } else {
            products = productRepository.findByTenantId(tenantId, pageable);
        }

        Map<String, java.math.BigDecimal> rates = hsnTaxRateService.ratesForProducts(products.getContent());
        Page<ProductResponse> response = products.map(product -> mapToResponse(product, rates));

        return ResponseEntity.ok(response);
    }

    private ProductResponse mapToResponse(Product product, Map<String, java.math.BigDecimal> rates) {
        Integer tenantId = product.getTenant() != null ? product.getTenant().getId() : null;
        String hsn = HsnTaxRateService.normalizeCode(product.getHsnSac());
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getSku(),
                hsn,
                product.getQuantity(),
                product.getMinStock(),
                product.getPrice(),
                product.getMrp() != null ? product.getMrp() : product.getPrice(),
                product.getSetSize() != null ? product.getSetSize() : "1",
                product.getPoNumber(),
                product.getSalesCount() != null ? product.getSalesCount() : 0,
                product.getCategory(),
                product.getCreatedAt(),
                tenantId,
                product.getUploadedBy() != null ? product.getUploadedBy().getId() : null,
                rates.get(hsn),
                hsnTaxRateService.rateStatus(hsn, rates)
        );
    }
    @PostMapping
    public ResponseEntity<?> addProduct(@RequestBody ProductUpsertRequest product) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        Product entity = new Product();
        entity.setName(product.getName());
        entity.setSku(product.getSku());
        entity.setHsnSac(product.getHsnSac());
        entity.setQuantity(product.getQuantity());
        entity.setMinStock(product.getMinStock());
        entity.setPrice(product.getPrice());
        entity.setMrp(product.getMrp());
        entity.setSetSize(product.getSetSize());
        entity.setPoNumber(product.getPoNumber());
        entity.setSalesCount(product.getSalesCount());
        entity.setCategory(product.getCategory());
        entity.setExpiryDate(product.getExpiryDate());

        String resolvedCode = resolveCatalogCode(entity);
        if (resolvedCode == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "HSN/SAC is required"
            ));
        }
        entity.setSku(resolvedCode);
        entity.setHsnSac(normalizeSku(entity.getHsnSac()));

        String poNumber = entity.getPoNumber() == null ? "" : entity.getPoNumber().trim();
        if (productRepository.existsBySkuAndPoNumberAndTenantId(resolvedCode, poNumber, tenantId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "message", "HSN/SAC already exists for this tenant and PO"
            ));
        }

        Tenant tenant = new Tenant();
        tenant.setId(Math.toIntExact(tenantId));
        entity.setTenant(tenant);

        if (entity.getCategory() == null || entity.getCategory().isBlank()) {
            entity.setCategory("General");
        }
        if (entity.getMrp() == null) {
            entity.setMrp(entity.getPrice());
        }
        if (entity.getSetSize() == null || entity.getSetSize().isBlank()) {
            entity.setSetSize("1");
        }
        if (entity.getSalesCount() == null || entity.getSalesCount() < 0) {
            entity.setSalesCount(0);
        }
        if (entity.getQuantity() == null || entity.getQuantity() < 0) {
            entity.setQuantity(0);
        }

        int initialQuantity = entity.getQuantity();
        entity.setQuantity(0);
        Product savedProduct = productRepository.save(entity);
        if (initialQuantity > 0) {
            savedProduct = inventorySecurityService.applyMovement(
                    savedProduct.getId(),
                    initialQuantity,
                    InventoryMovement.MovementType.ADJUSTMENT,
                    "PRODUCT_CREATE",
                    String.valueOf(savedProduct.getId()),
                    "Initial stock set during product creation",
                    false
            );
        }
        auditSuccess("INVENTORY_ADJUSTMENT", String.valueOf(savedProduct.getId()), "Product created");
        return ResponseEntity.status(HttpStatus.CREATED).body(savedProduct);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> updateProduct(@PathVariable Long id, @RequestBody ProductUpsertRequest product) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        Product requestData = new Product();
        requestData.setName(product.getName());
        requestData.setSku(product.getSku());
        requestData.setHsnSac(product.getHsnSac());
        requestData.setQuantity(product.getQuantity());
        requestData.setMinStock(product.getMinStock());
        requestData.setPrice(product.getPrice());
        requestData.setMrp(product.getMrp());
        requestData.setSetSize(product.getSetSize());
        requestData.setPoNumber(product.getPoNumber());
        requestData.setSalesCount(product.getSalesCount());
        requestData.setCategory(product.getCategory());
        requestData.setExpiryDate(product.getExpiryDate());

        String resolvedCode = resolveCatalogCode(requestData);
        if (resolvedCode == null) {
            return ResponseEntity.badRequest().build();
        }

        String poNumber = requestData.getPoNumber() == null ? "" : requestData.getPoNumber().trim();
        if (productRepository.existsBySkuAndPoNumberAndTenantIdAndIdNot(resolvedCode, poNumber, tenantId, id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        return productRepository.findByIdAndTenantId(id, tenantId)
                .map(existing -> {
                    Integer oldQty = existing.getQuantity();
                    existing.setName(requestData.getName());
                    existing.setSku(resolvedCode);
                    existing.setMinStock(requestData.getMinStock());
                    existing.setExpiryDate(requestData.getExpiryDate());
                    existing.setPrice(requestData.getPrice());
                    existing.setMrp(requestData.getMrp() != null ? requestData.getMrp() : requestData.getPrice());
                    existing.setHsnSac(normalizeSku(requestData.getHsnSac()));
                    existing.setSetSize(requestData.getSetSize() == null || requestData.getSetSize().isBlank() ? "1" : requestData.getSetSize());
                    existing.setPoNumber(requestData.getPoNumber());
                    if (requestData.getSalesCount() != null && requestData.getSalesCount() >= 0) {
                        existing.setSalesCount(requestData.getSalesCount());
                    }
                    existing.setCategory(requestData.getCategory());
                    Product saved = productRepository.save(existing);

                    int beforeQty = oldQty == null ? 0 : oldQty;
                    int targetQty = requestData.getQuantity() == null ? beforeQty : requestData.getQuantity();
                    int delta = targetQty - beforeQty;
                    if (delta != 0) {
                        inventorySecurityService.applyMovement(
                                saved.getId(),
                                delta,
                                InventoryMovement.MovementType.ADJUSTMENT,
                                "PRODUCT_UPDATE",
                                String.valueOf(saved.getId()),
                                "Stock adjusted through product update",
                                false
                        );
                    }

                    String detail = "Product updated";
                    if (oldQty != null && delta != 0) {
                        detail = "Product quantity adjusted from " + oldQty + " to " + targetQty;
                    }
                    auditSuccess("INVENTORY_ADJUSTMENT", String.valueOf(saved.getId()), detail);
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private String normalizeSku(String sku) {
        if (sku == null) {
            return null;
        }
        String normalized = sku.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String resolveCatalogCode(Product product) {
        String sku = normalizeSku(product.getSku());
        if (sku != null) {
            return sku;
        }
        return normalizeSku(product.getHsnSac());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        return productRepository.findByIdAndTenantId(id, tenantId)
                .map(existing -> {
                    int qty = existing.getQuantity() == null ? 0 : existing.getQuantity();
                    if (qty > 0) {
                        inventorySecurityService.applyMovement(
                                existing.getId(),
                                -qty,
                                InventoryMovement.MovementType.WRITE_OFF,
                                "PRODUCT_DELETE",
                                String.valueOf(existing.getId()),
                                "Stock written off during product delete",
                                false
                        );
                    }
                    productRepository.delete(existing);
                    auditSuccess("INVENTORY_WRITE_OFF", String.valueOf(id), "Product deleted");
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }
}