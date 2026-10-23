package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.service.SellService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
@RestController
@CrossOrigin(originPatterns  = "*")

//@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})  // Vite + CRA
@RequestMapping("/api")
public class SellController
{
    @Autowired
    SellService salesService; // Assume this is @Autowired

    @GetMapping("/sales/last-six-months")
    public ResponseEntity<List<Map<String, Object>>> getMonthlySales() {
        List<Map<String, Object>> salesData = salesService.getLastSixMonthsSales();
         // [{"month": "Apr", "sales": 2780}, {"month": "Mar", "sales": 2000}]
        return ResponseEntity.ok(salesData);
    }
    /*
    @GetMapping("/invoices")
    public ResponseEntity<Page<Invoice>> getInvoices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return ResponseEntity.ok(invoiceRepo.findAll(pageable));
    }

     */
}
