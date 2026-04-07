package org.ib.ibbackend.controller;


import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

@RestController
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:*"})
@RequestMapping("/api/billing")
public class BillingController {

    @Autowired
    private ProductRepository productRepository;

    // Reuse /api/products for billing (size=1000)
    @GetMapping("/products")
    public ResponseEntity<Page<Product>> getBillingProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "510") int size) {  // 👈 Large for billing
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ResponseEntity.ok(productRepository.findAll(pageable));
    }
}