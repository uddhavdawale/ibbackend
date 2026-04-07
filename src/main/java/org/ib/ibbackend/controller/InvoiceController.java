package org.ib.ibbackend.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@RestController
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:*"})
@RequestMapping("/api/invoices")
public class InvoiceController {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private ProductRepository productRepository;

    // 👈 CREATE INVOICE + STOCK DEDUCTION
    @PostMapping
    @Transactional
    public ResponseEntity<?> createInvoice(@RequestBody Invoice invoiceRequest) {
        try {
            // Step 1: Validate & deduct stock
            for (InvoiceItem item : invoiceRequest.getItems()) {
                Product product = productRepository.findById(item.getProductId())
                        .orElseThrow(() -> new RuntimeException("Product not found: " + item.getProductId()));

                int newStock = product.getQuantity() - item.getQty();
                if (newStock < 0) {
                    return ResponseEntity.badRequest()
                            .body("Insufficient stock for " + item.getName() + ". Available: " + product.getQuantity());
                }

                product.setQuantity(newStock);
                productRepository.save(product);
            }

            // Step 2: Calculate totals
            BigDecimal subtotal = invoiceRequest.getItems().stream()
                    .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQty())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal gstAmount = invoiceRequest.getItems().stream()
                    .map(item -> item.getPrice()
                            .multiply(BigDecimal.valueOf(item.getQty()))
                            .multiply(BigDecimal.valueOf(item.getGstRate()))
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal grandTotal = subtotal.add(gstAmount);

            // Step 3: Save invoice
            Invoice invoice = new Invoice(
                    invoiceRequest.getInvoiceNo(),
                    invoiceRequest.getCustomerName(),
                    invoiceRequest.getCustomerPhone(),
                    LocalDate.now(),
                    invoiceRequest.getPaymentMethod(),
                    subtotal.setScale(2, RoundingMode.HALF_UP),
                    gstAmount.setScale(2, RoundingMode.HALF_UP),
                    grandTotal.setScale(2, RoundingMode.HALF_UP),
                    invoiceRequest.getItems()
            );

            Invoice saved = invoiceRepository.save(invoice);
            return ResponseEntity.ok(saved);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    // 👈 GET ALL INVOICES (Pagination)
    @GetMapping
    public ResponseEntity<Page<Invoice>> getInvoices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "") String search) {

        Sort sort = Sort.by("id").descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Invoice> invoices;
        if (!search.isEmpty()) {
            invoices = invoiceRepository.findByInvoiceNoContainingIgnoreCase(search, pageable);
        } else {
            invoices = invoiceRepository.findAll(pageable);
        }

        return ResponseEntity.ok(invoices);
    }

    // 👈 GET SINGLE INVOICE
    @GetMapping("/{id}")
    public ResponseEntity<Invoice> getInvoice(@PathVariable Long id) {
        return invoiceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    // InvoiceController.java
  /*  @GetMapping("/pdf/{invoiceNo}")
    public void downloadPDF(@PathVariable String invoiceNo, HttpServletResponse response) {
        byte[] pdf = pdfService.generate(invoiceNo);
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=invoice.pdf");
        response.getOutputStream().write(pdf);
    }*/
}