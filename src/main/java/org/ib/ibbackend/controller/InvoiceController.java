package org.ib.ibbackend.controller;

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
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.InventorySecurityService;
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
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(originPatterns  = "*")
//@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:8080"})
@RequestMapping({"/api/invoices", "/api/orders", "/api/invoice"})
public class InvoiceController {
    private static final Logger log = LoggerFactory.getLogger(InvoiceController.class);
    private static final int MAX_INVOICE_PAGE_SIZE = 200;
    private static final int MAX_INVOICE_SEARCH_LENGTH = 100;

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

    @Autowired(required = false)
    private AuditLogService auditLogService;
    @Autowired(required = false)
    private InventorySecurityService inventorySecurityService;

    @Autowired(required = false)
    private org.ib.ibbackend.service.CustomerProfileService customerProfileService;

    private void auditSuccess(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.success(action, resourceId, detail);
        }
    }

    private void auditFailure(String action, String resourceId, String detail) {
        if (auditLogService != null) {
            auditLogService.failure(action, resourceId, detail);
        }
    }

    private void deductStockForSale(InvoiceItem item, String invoiceNo, Long tenantId) {
        if (inventorySecurityService != null) {
            inventorySecurityService.applyMovement(
                    item.getProductId(),
                    -item.getQty(),
                    org.ib.ibbackend.entity.InventoryMovement.MovementType.SALE,
                    "INVOICE",
                    invoiceNo,
                    "Stock deducted for GST invoice sale",
                    false
            );
            return;
        }

        Optional<Product> tenantScoped = productRepository.findByIdAndTenantIdForUpdate(item.getProductId(), tenantId);
        if (tenantScoped == null) {
            tenantScoped = Optional.empty();
        }

        Product product = tenantScoped.orElseGet(() -> productRepository.findById(item.getProductId())
            .filter(p -> p.getTenant() == null
                || p.getTenant().getId() == Math.toIntExact(tenantId))
            .orElseThrow(() -> new RuntimeException("Product not found for tenant: " + item.getProductId())));
        int currentQty = product.getQuantity() == null ? 0 : product.getQuantity();
        int newStock = currentQty - item.getQty();
        if (newStock < 0) {
            throw new RuntimeException("Insufficient stock for " + item.getName() + ". Available: " + currentQty);
        }
        product.setQuantity(newStock);
        productRepository.save(product);
    }

    private void restoreStockOnCancel(InvoiceItem item, String invoiceNo, Long tenantId) {
        int restoreQty = item.getQty() == null ? 0 : item.getQty();
        if (inventorySecurityService != null) {
            inventorySecurityService.applyMovement(
                    item.getProductId(),
                    restoreQty,
                    org.ib.ibbackend.entity.InventoryMovement.MovementType.SALE_CANCEL,
                    "INVOICE_CANCEL",
                    invoiceNo,
                    "Stock restored for GST sell order cancellation",
                    false
            );
            return;
        }

        Optional<Product> tenantScoped = productRepository.findByIdAndTenantIdForUpdate(item.getProductId(), tenantId);
        if (tenantScoped == null) {
            tenantScoped = Optional.empty();
        }

        Product product = tenantScoped.orElseGet(() -> productRepository.findById(item.getProductId())
            .filter(p -> p.getTenant() == null
                || p.getTenant().getId() == Math.toIntExact(tenantId))
            .orElseThrow(() -> new RuntimeException("Product not found for tenant: " + item.getProductId())));
        int currentQty = product.getQuantity() == null ? 0 : product.getQuantity();
        product.setQuantity(currentQty + restoreQty);
        productRepository.save(product);
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<?> cancelSellOrder(@PathVariable Long id) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        return invoiceRepository.findByIdAndTenantId(id, tenantId)
                .map(invoice -> {
                    if (invoice.getStatus() == Invoice.InvoiceStatus.CANCELLED) {
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("success", true);
                        payload.put("message", "Sell order already cancelled");
                        payload.put("invoiceId", invoice.getId());
                        payload.put("invoiceNo", invoice.getInvoiceNo());
                        payload.put("status", "CANCELLED");
                        return ResponseEntity.ok(payload);
                    }

                    List<InvoiceItem> items = invoice.getItems() == null ? List.of() : invoice.getItems();
                    for (InvoiceItem item : items) {
                        restoreStockOnCancel(item, invoice.getInvoiceNo(), tenantId);
                    }

                    invoice.setStatus(Invoice.InvoiceStatus.CANCELLED);
                    invoice.setPaymentStatus(Invoice.PaymentStatus.CANCELLED);
                    Invoice saved = invoiceRepository.save(invoice);
                    auditSuccess("INVOICE_CANCEL", saved.getInvoiceNo(), "GST sell order cancelled and stock restored");

                    Map<String, Object> payload = new HashMap<>();
                    payload.put("success", true);
                    payload.put("message", "Sell order cancelled successfully");
                    payload.put("invoiceId", saved.getId());
                    payload.put("invoiceNo", saved.getInvoiceNo());
                    payload.put("status", "CANCELLED");
                    payload.put("restockedItems", items.size());
                    return ResponseEntity.ok(payload);
                })
                .orElseGet(() -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("success", false);
                    payload.put("message", "Sell order not found");
                    payload.put("invoiceId", id);
                    return ResponseEntity.status(404).body(payload);
                });
    }

    @GetMapping
    public ResponseEntity<Page<Map<String, Object>>> getInvoices(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String billType,
            @RequestParam(defaultValue = "") String search) {

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 10 : Math.min(size, MAX_INVOICE_PAGE_SIZE);
        String safeSearch = search == null ? "" : search.trim();
        if (safeSearch.length() > MAX_INVOICE_SEARCH_LENGTH) {
            safeSearch = safeSearch.substring(0, MAX_INVOICE_SEARCH_LENGTH);
        }

        log.info("Fetching invoices page={} size={} search={} status={} for tenant={}", safePage, safeSize, safeSearch, status, tenantId);
        Sort sort = Sort.by("id").descending();
        Pageable pageable = PageRequest.of(safePage, safeSize, sort);

        Page<Invoice> invoices;
        if (!safeSearch.isEmpty()) {
            invoices = invoiceRepository.findBySearchAndTenantId(tenantId, safeSearch, pageable);
        } else {
            invoices = invoiceRepository.findByTenantId(tenantId, pageable);
        }

        if (status != null && !status.isBlank()) {
            invoices = invoices.getContent().stream()
                    .filter(invoice -> invoice.getStatus() != null && invoice.getStatus().name().equalsIgnoreCase(status))
                    .collect(Collectors.collectingAndThen(Collectors.toList(), list -> new org.springframework.data.domain.PageImpl<>(list, pageable, list.size())));
        }

        if (billType != null && !billType.isBlank()) {
            String requested = billType.trim().toLowerCase();
            if ("non-gst".equals(requested) || "non_gst".equals(requested) || "non gst".equals(requested)) {
                invoices = new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0);
            } else if (!"gst".equals(requested)
                    && !"all".equals(requested)
                    && !"all-invoices".equals(requested)
                    && !"combined".equals(requested)
                    && !"both".equals(requested)) {
                invoices = new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0);
            }
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
            summary.put("billType", "gst");
            summary.put("isGstInvoice", true);
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
            Long tenantId = TenantContext.getCurrentTenant();
            if (tenantId == null) {
                throw new org.springframework.security.access.AccessDeniedException("Access denied");
            }

            for (InvoiceItem item : invoiceRequest.getItems()) {
                deductStockForSale(item, invoiceNo, tenantId);
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
                    .map(item -> item.getItemSubtotal() == null ? BigDecimal.ZERO : item.getItemSubtotal())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gstAmount = invoiceItems.stream()
                    .map(item -> item.getItemGst() == null ? BigDecimal.ZERO : item.getItemGst())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grandTotal = subtotal.add(gstAmount);

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
            if (customerProfileService != null) {
                customerProfileService.syncFromInvoice(saved);
            }
            int savedItemsCount = saved.getItems() == null ? 0 : saved.getItems().size();
            log.info("Invoice saved successfully: {} with {} items", saved.getInvoiceNo(), savedItemsCount);
            auditSuccess("INVOICE_CREATE", saved.getInvoiceNo(), "GST invoice created");
            return ResponseEntity.ok(saved);
        } catch (RuntimeException e) {
            log.error("Error saving invoice", e);
            auditFailure("INVOICE_CREATE", "N/A", "Invoice save failed: " + e.getClass().getSimpleName());
            throw e;
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