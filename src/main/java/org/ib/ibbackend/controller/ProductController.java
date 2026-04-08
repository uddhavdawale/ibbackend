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
import java.time.LocalDateTime;

@RestController
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:*","https://ibfrontendlive.vercel.app/"})
@RequestMapping("/api/products")
public class ProductController {

    @Autowired
    private ProductRepository productRepository;

    @GetMapping
    public ResponseEntity<Page<Product>> getProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("desc") ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Product> products;
        if (!search.isEmpty()) {
            products = productRepository.findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(search, search, pageable);
        } else {
            products = productRepository.findAll(pageable);
        }
        return ResponseEntity.ok(products);
    }
    private BigDecimal price;
    @PostMapping
    public ResponseEntity<Product> addProduct(@RequestBody Product product) {
        // Mandatory: name, quantity, price
        if (product.getName() == null || product.getName().trim().isEmpty() ||
                product.getQuantity() == null || product.getPrice() == null) {
            return ResponseEntity.badRequest().build();
        }

        product.setCreatedAt(LocalDateTime.now());
        Product saved = productRepository.save(product);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> updateProduct(@PathVariable Long id, @RequestBody Product product) {
        return productRepository.findById(id)
                .map(existing -> {
                    existing.setName(product.getName());
                    existing.setSku(product.getSku());
                    existing.setQuantity(product.getQuantity());
                    existing.setMinStock(product.getMinStock());
                    existing.setExpiryDate(product.getExpiryDate());
                    existing.setPrice(product.getPrice());
                    return ResponseEntity.ok(productRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        if (productRepository.existsById(id)) {
            productRepository.deleteById(id);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
}
