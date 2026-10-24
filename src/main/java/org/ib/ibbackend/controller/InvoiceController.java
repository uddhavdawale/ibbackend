package org.ib.ibbackend.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.ib.ibbackend.dto.InvoiceRequest;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InvoiceItemRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.service.SequenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(originPatterns  = "*")
//@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:8080"})
@RequestMapping("/api/invoices")
public class InvoiceController {
    private static final Logger log = LoggerFactory.getLogger(InvoiceController.class);

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private InvoiceItemRepository invoiceItemRepository;

    @Autowired
    private ProductRepository productRepository;
    @Autowired private
    SequenceService seqService;

    public ResponseEntity<?> createInvoice(@RequestBody Invoice invoiceRequest) {
        try {
            String invoiceNo = String.format("INV-%d/%04d",
                    java.time.Year.now().getValue(),
                    seqService.nextValue("invoice"));
            invoiceRequest.setInvoiceNo(invoiceNo);

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
            for (InvoiceItem item : invoiceItems) {
                item.setInvoice(invoice);
            }

            Invoice saved = invoiceRepository.save(invoice);
            log.info("Invoice created successfully: {} with {} items", saved.getInvoiceNo(), saved.getItems().size());
            return ResponseEntity.ok(saved);

        } catch (Exception e) {
            log.error("Error creating invoice", e);
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<Page<Map<String, Object>>> getInvoices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "") String search) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        log.info("Fetching invoices page={} size={} search={} status={} for tenant={}", page, size, search, status, tenantId);
        Sort sort = Sort.by("id").descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Invoice> invoices;
        if (!search.isEmpty()) {
            invoices = invoiceRepository.findBySearchAndTenantId(tenantId, search, pageable);
        } else {
            invoices = invoiceRepository.findByTenantId(tenantId, pageable);
        }

        if (status != null && !status.isBlank()) {
            invoices = invoices.getContent().stream()
                    .filter(invoice -> invoice.getStatus() != null && invoice.getStatus().name().equalsIgnoreCase(status))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), list -> new org.springframework.data.domain.PageImpl<>(list, pageable, list.size())));
        }

        Page<Map<String, Object>> summaryPage = invoices.map(invoice -> {
            Map<String, Object> summary = new HashMap<>();
            summary.put("id", invoice.getId());
            summary.put("total", invoice.getGrandTotal() != null ? invoice.getGrandTotal() : BigDecimal.ZERO);
            summary.put("status", normalizeStatus(invoice.getStatus()));
            summary.put("customerName", invoice.getCustomerName());
            summary.put("createdAt", invoice.getDate() != null ? invoice.getDate().atStartOfDay() : null);
            return summary;
        });

        return ResponseEntity.ok(summaryPage);
    }

    private String normalizeStatus(Invoice.InvoiceStatus status) {
        if (status == null) {
            return "Pending";
        }
        return switch (status.name().toUpperCase()) {
            case "PAID" -> "Paid";
            case "PENDING" -> "Pending";
            case "CANCELLED" -> "Cancelled";
            default -> status.name();
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<Invoice> getInvoice(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        log.info("Fetching invoice by id={} for tenant={}", id, tenantId);
        return invoiceRepository.findByIdAndTenantId(id, tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createInvoice(@RequestBody InvoiceRequest invoiceRequest) {
        try {
            String invoiceNo = String.format("INV-%d/%04d",
                    java.time.Year.now().getValue(),
                    seqService.nextValue("invoice"));

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

            List<InvoiceItem> invoiceItems = invoiceRequest.getItems().stream()
                    .map(dto -> {
                        InvoiceItem item = new InvoiceItem(dto.getProductId(), dto.getName(),
                                dto.getSku(), dto.getHsn(), dto.getGstRate(),
                                dto.getPrice(), dto.getQty());
                        return item;
                    })
                    .collect(Collectors.toList());

            BigDecimal subtotal = invoiceItems.stream()
                    .map(InvoiceItem::getItemSubtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gstAmount = invoiceItems.stream()
                    .map(InvoiceItem::getItemGst)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grandTotal = subtotal.add(gstAmount);

            Long tenantId = TenantContext.getCurrentTenant();
            if (tenantId == null) {
                throw new org.springframework.security.access.AccessDeniedException("Access denied");
            }

            Invoice invoice = new Invoice(invoiceNo, invoiceRequest.getCustomerName(),
                    invoiceRequest.getCustomerPhone(), LocalDate.now(),
                    invoiceRequest.getPaymentMethod(), subtotal, gstAmount, grandTotal,
                    invoiceItems);
            Tenant tenant = new Tenant();
            tenant.setId(Math.toIntExact(tenantId));
            invoice.setTenant(tenant);

            for (InvoiceItem item : invoiceItems) {
                item.setInvoice(invoice);
            }

            Invoice saved = invoiceRepository.save(invoice);
            log.info("Invoice saved successfully: {} with {} items", saved.getInvoiceNo(), saved.getItems().size());
            return ResponseEntity.ok(saved);

        } catch (Exception e) {
            log.error("Error saving invoice", e);
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }
}