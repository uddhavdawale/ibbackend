package org.ib.ibbackend.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.ib.ibbackend.dto.InvoiceRequest;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InvoiceItemRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.SequenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
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

    @Autowired
    private UserRepository userRepository;

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
            hydrateInvoiceOwnership(invoice);
            Map<String, Object> summary = new HashMap<>();
            BigDecimal totalAmount = invoice.getGrandTotal() != null ? invoice.getGrandTotal() : BigDecimal.ZERO;
            BigDecimal paidAmount = invoice.getPaidAmount();
            BigDecimal outstandingAmount = invoice.getOutstandingAmount();
            Invoice.PaymentStatus paymentStatus = invoice.getPaymentStatus();

            summary.put("id", invoice.getId());
            summary.put("invoiceNo", invoice.getInvoiceNo());
            summary.put("date", invoice.getDate());
            summary.put("customerName", invoice.getCustomerName());
            summary.put("customerPhone", invoice.getCustomerPhone());
            summary.put("customerEmail", invoice.getCustomerEmail());
            summary.put("paymentMethod", invoice.getPaymentMethod());
            summary.put("subtotal", invoice.getSubtotal() != null ? invoice.getSubtotal() : BigDecimal.ZERO);
            summary.put("gstAmount", invoice.getGstAmount() != null ? invoice.getGstAmount() : BigDecimal.ZERO);
            summary.put("grandTotal", totalAmount);
            summary.put("totalAmount", totalAmount);
            summary.put("paidAmount", paidAmount);
            summary.put("outstandingAmount", outstandingAmount);
            summary.put("paymentStatus", paymentStatus.name());
            summary.put("status", normalizeStatus(invoice.getStatus()));
            summary.put("tenantId", invoice.getTenantId());
            summary.put("userId", invoice.getUserId());
            summary.put("items", invoice.getItems() != null ? invoice.getItems() : List.of());
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
                .map(invoice -> {
                    hydrateInvoiceOwnership(invoice);
                    return ResponseEntity.ok(invoice);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private void hydrateInvoiceOwnership(Invoice invoice) {
        if (invoice == null) {
            return;
        }
        if (invoice.getUserId() == null && userRepository != null) {
            String email = SecurityContextHolder.getContext().getAuthentication() != null
                    ? SecurityContextHolder.getContext().getAuthentication().getName()
                    : null;
            if (email != null && !email.isBlank()) {
                userRepository.findByEmail(email).ifPresent(user -> {
                    invoice.setUserId(user.getId());
                    invoice.setCustomerEmail(user.getEmail());
                });
            }
        }
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

            BigDecimal paidAmount = resolvePaidAmount(
                    grandTotal,
                    invoiceRequest.getPaidAmount(),
                    invoiceRequest.getStatus(),
                    invoiceRequest.getPaymentStatus()
            );

            String clientStatusSummary = String.format("status=%s, paymentStatus=%s, paidAmount=%s",
                    invoiceRequest.getStatus(), invoiceRequest.getPaymentStatus(), invoiceRequest.getPaidAmount());
            log.info("Checkout payment intent for tenant {}: {}", tenantId, clientStatusSummary);

            Invoice invoice = new Invoice(invoiceNo, invoiceRequest.getCustomerName(),
                    invoiceRequest.getCustomerPhone(), LocalDate.now(),
                    invoiceRequest.getPaymentMethod(), subtotal, gstAmount, grandTotal,
                    invoiceItems);
            invoice.setPaidAmount(paidAmount);
            invoice.setPaymentStatus(resolvePaymentStatus(paidAmount, grandTotal));
            invoice.setStatus(resolveInvoiceStatus(invoice.getPaymentStatus()));
            Tenant tenant = new Tenant();
            tenant.setId(Math.toIntExact(tenantId));
            invoice.setTenant(tenant);

            String email = SecurityContextHolder.getContext().getAuthentication() != null
                    ? SecurityContextHolder.getContext().getAuthentication().getName()
                    : null;
            if (email != null && !email.isBlank() && userRepository != null) {
                User currentUser = userRepository.findByEmail(email).orElse(null);
                if (currentUser != null) {
                    invoice.setUserId(currentUser.getId());
                    invoice.setCustomerEmail(currentUser.getEmail());
                }
            }

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

    private Invoice.PaymentStatus resolvePaymentStatus(BigDecimal paidAmount, BigDecimal grandTotal) {
        BigDecimal total = grandTotal == null ? BigDecimal.ZERO : grandTotal;
        BigDecimal paid = paidAmount == null ? BigDecimal.ZERO : paidAmount.max(BigDecimal.ZERO);

        if (paid.compareTo(total) >= 0 && total.compareTo(BigDecimal.ZERO) > 0) {
            return Invoice.PaymentStatus.PAID;
        }
        if (paid.compareTo(BigDecimal.ZERO) > 0 && paid.compareTo(total) < 0) {
            return Invoice.PaymentStatus.PARTIAL;
        }
        return Invoice.PaymentStatus.PENDING;
    }

    private BigDecimal resolvePaidAmount(BigDecimal grandTotal, BigDecimal providedAmount, String status, String paymentStatus) {
        BigDecimal baseAmount = providedAmount == null ? BigDecimal.ZERO : providedAmount.max(BigDecimal.ZERO);
        String normalizedStatus = normalizePaymentIntent(status, paymentStatus);

        if (normalizedStatus == null) {
            return baseAmount;
        }

        if ("PAID".equals(normalizedStatus)) {
            return grandTotal == null ? baseAmount : grandTotal.max(baseAmount);
        }

        if ("PENDING".equals(normalizedStatus)) {
            return BigDecimal.ZERO;
        }

        if ("PARTIAL".equals(normalizedStatus)) {
            return grandTotal == null ? baseAmount : baseAmount.min(grandTotal);
        }

        return baseAmount;
    }

    private String normalizePaymentIntent(String status, String paymentStatus) {
        String candidate = status != null ? status : paymentStatus;
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String normalized = candidate.trim().toUpperCase();
        if ("PAID".equals(normalized) || "SUCCESS".equals(normalized) || "COMPLETED".equals(normalized)) {
            return "PAID";
        }
        if ("PENDING".equals(normalized) || "WAITING".equals(normalized)) {
            return "PENDING";
        }
        if ("PARTIAL".equals(normalized) || "PARTIALLY_PAID".equals(normalized) || "PARTIALLYPAID".equals(normalized)) {
            return "PARTIAL";
        }
        return null;
    }

    private Invoice.InvoiceStatus resolveInvoiceStatus(Invoice.PaymentStatus paymentStatus) {
        if (paymentStatus == null) {
            return Invoice.InvoiceStatus.PENDING;
        }
        return switch (paymentStatus) {
            case PAID -> Invoice.InvoiceStatus.PAID;
            case CANCELLED -> Invoice.InvoiceStatus.CANCELLED;
            default -> Invoice.InvoiceStatus.PENDING;
        };
    }
}