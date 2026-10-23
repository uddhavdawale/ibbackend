package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class ProductService {
    @Autowired
    private ProductRepository productRepo;
    public Page<Product> getProducts() {
        Long tenantId = TenantContext.getCurrentTenant();
        return productRepo.findByTenant(tenantId, PageRequest.of(0, 50));
    }
}