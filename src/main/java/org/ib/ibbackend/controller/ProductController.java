package org.ib.ibbackend.controller;


import org.ib.ibbackend.dto.ProductResponse;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.ProductRepository;
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

    @Autowired
    private ProductRepository productRepository;

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

        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Product> products;

        if (!search.isBlank()) {
            products = productRepository.findBySearchAndTenantId(tenantId, search, pageable);
        } else {
            products = productRepository.findByTenantId(tenantId, pageable);
        }

        Page<ProductResponse> response = products.map(this::mapToResponse);

        return ResponseEntity.ok(response);
    }

    private ProductResponse mapToResponse(Product product) {
        Integer tenantId = product.getTenant() != null ? product.getTenant().getId() : null;
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getSku(),
            product.getHsnSac(),
                product.getQuantity(),
                product.getMinStock(),
                product.getPrice(),
            product.getMrp() != null ? product.getMrp() : product.getPrice(),
            product.getSetSize() != null ? product.getSetSize() : 1,
            product.getPoNumber(),
            product.getSalesCount() != null ? product.getSalesCount() : 0,
                product.getCategory(),
                product.getCreatedAt(),
                tenantId
        );
    }
    @PostMapping
    public ResponseEntity<?> addProduct(@RequestBody Product product) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        String resolvedCode = resolveCatalogCode(product);
        if (resolvedCode == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "HSN/SAC is required"
            ));
        }
        product.setSku(resolvedCode);
        if (normalizeSku(product.getHsnSac()) == null) {
            product.setHsnSac(resolvedCode);
        }

        if (productRepository.existsBySkuAndTenantId(resolvedCode, tenantId)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "message", "HSN/SAC already exists for this tenant"
            ));
        }

        Tenant tenant = new Tenant();
        tenant.setId(Math.toIntExact(tenantId));
        product.setTenant(tenant);

        if (product.getCategory() == null || product.getCategory().isBlank()) {
            product.setCategory("General");
        }
        if (product.getMrp() == null) {
            product.setMrp(product.getPrice());
        }
        if (product.getSetSize() == null || product.getSetSize() <= 0) {
            product.setSetSize(1);
        }
        if (product.getSalesCount() == null || product.getSalesCount() < 0) {
            product.setSalesCount(0);
        }

        Product savedProduct = productRepository.save(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedProduct);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> updateProduct(@PathVariable Long id, @RequestBody Product product) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        String resolvedCode = resolveCatalogCode(product);
        if (resolvedCode == null) {
            return ResponseEntity.badRequest().build();
        }

        if (productRepository.existsBySkuAndTenantIdAndIdNot(resolvedCode, tenantId, id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        return productRepository.findByIdAndTenantId(id, tenantId)
                .map(existing -> {
                    existing.setName(product.getName());
                    existing.setSku(resolvedCode);
                    existing.setQuantity(product.getQuantity());
                    existing.setMinStock(product.getMinStock());
                    existing.setExpiryDate(product.getExpiryDate());
                    existing.setPrice(product.getPrice());
                    existing.setMrp(product.getMrp() != null ? product.getMrp() : product.getPrice());
                    existing.setHsnSac(normalizeSku(product.getHsnSac()) != null ? normalizeSku(product.getHsnSac()) : resolvedCode);
                    existing.setSetSize(product.getSetSize() == null || product.getSetSize() <= 0 ? 1 : product.getSetSize());
                    existing.setPoNumber(product.getPoNumber());
                    if (product.getSalesCount() != null && product.getSalesCount() >= 0) {
                        existing.setSalesCount(product.getSalesCount());
                    }
                    existing.setCategory(product.getCategory());
                    return ResponseEntity.ok(productRepository.save(existing));
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
                    productRepository.delete(existing);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }
}