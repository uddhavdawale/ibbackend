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

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
                product.getQuantity(),
                product.getMinStock(),
                product.getPrice(),
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

        Tenant tenant = new Tenant();
        tenant.setId(Math.toIntExact(tenantId));
        product.setTenant(tenant);

        if (product.getCategory() == null || product.getCategory().isBlank()) {
            product.setCategory("General");
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

        return productRepository.findByIdAndTenantId(id, tenantId)
                .map(existing -> {
                    existing.setName(product.getName());
                    existing.setSku(product.getSku());
                    existing.setQuantity(product.getQuantity());
                    existing.setMinStock(product.getMinStock());
                    existing.setExpiryDate(product.getExpiryDate());
                    existing.setPrice(product.getPrice());
                    existing.setCategory(product.getCategory());
                    return ResponseEntity.ok(productRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
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