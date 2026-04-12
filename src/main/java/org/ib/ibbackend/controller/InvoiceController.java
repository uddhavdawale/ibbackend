package org.ib.ibbackend.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.ib.ibbackend.dto.InvoiceRequest;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.repository.InvoiceItemRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.service.SequenceService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:8080"})
@RequestMapping("/api/invoices")
public class InvoiceController {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private InvoiceItemRepository invoiceItemRepository;

    @Autowired
    private ProductRepository productRepository;
    @Autowired private
    SequenceService seqService;
    // 👈 CREATE INVOICE + STOCK DEDUCTION

    public ResponseEntity<?> createInvoice(@RequestBody Invoice invoiceRequest) {
        try {
            String invoiceNo = String.format("INV-%d/%04d",
                    java.time.Year.now().getValue(),
                    seqService.nextValue("invoice"));
            invoiceRequest.setInvoiceNo(invoiceNo);

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
            List<InvoiceItem> invoiceItems = invoiceRequest.getItems().stream()
                    .map(dto -> {
                        InvoiceItem item = new InvoiceItem(
                                dto.getProductId(), dto.getName(), dto.getSku(),
                                dto.getHsn(), dto.getGstRate(), dto.getPrice(), dto.getQty()
                        );
                        return item;
                    })
                    .collect(Collectors.toList());

            // Step 2: Calculate totals

            BigDecimal subtotal = invoiceRequest.getItems().stream()
                    .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQty())))
                    .collect(Collectors.reducing(BigDecimal.ZERO, BigDecimal::add));


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
            // ✅ CRITICAL: Set bidirectional relationship
            for (InvoiceItem item : invoiceItems) {
                item.setInvoice(invoice);  // Sets invoice_id FK
            }

            Invoice saved = invoiceRepository.save(invoice);
            return ResponseEntity.ok(saved);

        } catch (Exception e) {
            e.printStackTrace();
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
    @PostMapping
    @Transactional
    public ResponseEntity<?> createInvoice(@RequestBody InvoiceRequest invoiceRequest) {
        try {
            String invoiceNo = String.format("INV-%d/%04d",
                    java.time.Year.now().getValue(),
                    seqService.nextValue("invoice"));

            // 1. Stock deduction (unchanged)
            for (InvoiceItem item : invoiceRequest.getItems()) {
                Product product = productRepository.findById(item.getProductId())
                        .orElseThrow(() -> new RuntimeException("Product not found: " + item.getProductId()));
                int newStock = product.getQuantity() - item.getQty();
                if (newStock < 0) {
                    return ResponseEntity.badRequest().body("Insufficient stock for " + item.getName());
                }
                product.setQuantity(newStock);
                productRepository.save(product);
            }

            // 2. ✅ Create & calculate items FIRST
            List<InvoiceItem> invoiceItems = invoiceRequest.getItems().stream()
                    .map(dto -> {
                        InvoiceItem item = new InvoiceItem(dto.getProductId(), dto.getName(),
                                dto.getSku(), dto.getHsn(), dto.getGstRate(),
                                dto.getPrice(), dto.getQty());
                        return item;
                    })
                    .collect(Collectors.toList());

            // Calculate totals from items
            BigDecimal subtotal = invoiceItems.stream()
                    .map(InvoiceItem::getItemSubtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gstAmount = invoiceItems.stream()
                    .map(InvoiceItem::getItemGst)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grandTotal = subtotal.add(gstAmount);

            // 3. ✅ Create invoice with items (no save yet)
            Invoice invoice = new Invoice(invoiceNo, invoiceRequest.getCustomerName(),
                    invoiceRequest.getCustomerPhone(), LocalDate.now(),
                    invoiceRequest.getPaymentMethod(), subtotal, gstAmount, grandTotal,
                    invoiceItems);

            // 4. ✅ Link items AFTER totals calculated
            for (InvoiceItem item : invoiceItems) {
                item.setInvoice(invoice);
            }

            // 5. ✅ SINGLE SAVE - cascade works!
            Invoice saved = invoiceRepository.save(invoice);
            System.out.println("✅ Saved " + saved.getInvoiceNo() +
                    " with " + saved.getItems().size() + " items");

            return ResponseEntity.ok(saved);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }
}