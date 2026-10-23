package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import  org.ib.ibbackend.repository.ProductRepository;

import java.util.List;

@RestController
@CrossOrigin(originPatterns  = "*")
//@CrossOrigin(origins = "http://localhost:5173")
public class TestController {

    @Autowired
    private ProductRepository productRepo;

    @GetMapping("/test-db")
    public String testDb() {
        long count = productRepo.count();
        return " DB Connected! Products: " + count;
    }

    @GetMapping("/products")
    public List<Product> getProducts() {
        return productRepo.findAll();
    }
}
