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
import org.ib.ibbackend.service.InvoiceNumberGenerator;
import org.ib.ibbackend.service.InvoiceIssuerResolver;
import org.ib.ibbackend.service.HsnTaxRateService;
import org.ib.ibbackend.service.PaymentCollectionService;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(originPatterns  = "*")
//@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:8080"})
@RequestMapping({"/api/invoices", "/api/orders", "/api/invoice"})
public class InvoiceController {
    private static final Logger log = LoggerFactory.getLogger(InvoiceController.class);
    private static final int MAX_INVOICE_PAGE_SIZE = 200;
    private static final int MAX_INVOICE_SEARCH_LENGTH = 100;
    private static final Map<String, String> GST_STATE_CODES = Map.ofEntries(
            Map.entry("01", "01"), Map.entry("jammuandkashmir", "01"),
            Map.entry("02", "02"), Map.entry("himachalpradesh", "02"),
            Map.entry("03", "03"), Map.entry("punjab", "03"),
            Map.entry("04", "04"), Map.entry("chandigarh", "04"),
            Map.entry("05", "05"), Map.entry("uttarakhand", "05"), Map.entry("uttaranchal", "05"),
            Map.entry("06", "06"), Map.entry("haryana", "06"),
            Map.entry("07", "07"), Map.entry("delhi", "07"), Map.entry("nationalcapitalterritoryofdelhi", "07"),
            Map.entry("08", "08"), Map.entry("rajasthan", "08"),
            Map.entry("09", "09"), Map.entry("uttarpradesh", "09"),
            Map.entry("10", "10"), Map.entry("bihar", "10"),
            Map.entry("11", "11"), Map.entry("sikkim", "11"),
            Map.entry("12", "12"), Map.entry("arunachalpradesh", "12"),
            Map.entry("13", "13"), Map.entry("nagaland", "13"),
            Map.entry("14", "14"), Map.entry("manipur", "14"),
            Map.entry("15", "15"), Map.entry("mizoram", "15"),
            Map.entry("16", "16"), Map.entry("tripura", "16"),
            Map.entry("17", "17"), Map.entry("meghalaya", "17"),
            Map.entry("18", "18"), Map.entry("assam", "18"),
            Map.entry("19", "19"), Map.entry("westbengal", "19"),
            Map.entry("20", "20"), Map.entry("jharkhand", "20"),
            Map.entry("21", "21"), Map.entry("odisha", "21"), Map.entry("orissa", "21"),
            Map.entry("22", "22"), Map.entry("chhattisgarh", "22"),
            Map.entry("23", "23"), Map.entry("madhyapradesh", "23"),
            Map.entry("24", "24"), Map.entry("gujarat", "24"),
            Map.entry("25", "25"), Map.entry("damananddiu", "25"),
            Map.entry("26", "26"), Map.entry("dadranagarhavelianddamananddiu", "26"),
            Map.entry("dadranagarhaveli", "26"),
            Map.entry("27", "27"), Map.entry("maharashtra", "27"),
            Map.entry("28", "28"), Map.entry("andhrapradeshbefore2014", "28"),
            Map.entry("29", "29"), Map.entry("karnataka", "29"),
            Map.entry("30", "30"), Map.entry("goa", "30"),
            Map.entry("31", "31"), Map.entry("lakshadweep", "31"),
            Map.entry("32", "32"), Map.entry("kerala", "32"),
            Map.entry("33", "33"), Map.entry("tamilnadu", "33"),
            Map.entry("34", "34"), Map.entry("puducherry", "34"), Map.entry("pondicherry", "34"),
            Map.entry("35", "35"), Map.entry("andamanandnicobarislands", "35"),
            Map.entry("36", "36"), Map.entry("telangana", "36"),
            Map.entry("37", "37"), Map.entry("andhrapradesh", "37"),
            Map.entry("38", "38"), Map.entry("ladakh", "38"));

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private InvoiceItemRepository invoiceItemRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired(required = false)
    private AuditLogService auditLogService;
    @Autowired(required = false)
    private InventorySecurityService inventorySecurityService;

    @Autowired
    private HsnTaxRateService hsnTaxRateService;

    @Autowired(required = false)
    private org.ib.ibbackend.service.CustomerProfileService customerProfileService;
    @Autowired
    private PaymentCollectionService paymentCollectionService;

    @Autowired
    private org.ib.ibbackend.service.InvoiceIdempotencyService invoiceIdempotencyService;

    @Autowired
    private org.ib.ibbackend.service.SellOrderAdjustmentSupport sellOrderAdjustmentSupport;

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

        Product product = tenantScoped.orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Product not found for tenant: " + item.getProductId()));
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

        Product product = tenantScoped.orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "Product not found for tenant: " + item.getProductId()));
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

        return invoiceRepository.findByIdAndTenantIdForUpdate(id, tenantId)
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

                    sellOrderAdjustmentSupport.assertCanCancel(id, tenantId, "gst");
                    List<InvoiceItem> items = invoice.getItems() == null ? List.of() : invoice.getItems();
                    for (InvoiceItem item : items) {
                        restoreStockOnCancel(item, invoice.getInvoiceNo(), tenantId);
                    }

                    invoice.setStatus(Invoice.InvoiceStatus.CANCELLED);
                    invoice.setPaymentStatus(Invoice.PaymentStatus.CANCELLED);
                    Invoice saved = invoiceRepository.save(invoice);
                    if (customerProfileService != null) {
                        customerProfileService.refreshLedgerForInvoice(saved);
                    }
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
            summary.put("customerAddress", invoice.getCustomerAddress());
            summary.put("placeOfSupplyState", invoice.getPlaceOfSupplyState());
            summary.put("issuerType", invoice.getIssuerType());
            summary.put("issuerName", invoice.getIssuerName());
            summary.put("issuerGstin", invoice.getIssuerGstin());
            summary.put("issuerAddress", invoice.getIssuerAddress());
            summary.put("issuerPhone", invoice.getIssuerPhone());
            summary.put("issuerEmail", invoice.getIssuerEmail());
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
                });
            }
        }
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createInvoice(
            @RequestBody InvoiceRequest invoiceRequest,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        try {
            if (invoiceRequest == null) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Request body is required");
            }

            if (SecurityContextHolder.getContext().getAuthentication() == null
                    || !SecurityContextHolder.getContext().getAuthentication().isAuthenticated()
                    || "anonymousUser".equals(SecurityContextHolder.getContext().getAuthentication().getPrincipal())) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Authenticated user is required");
            }

            Long tenantId = TenantContext.getCurrentTenant();
            if (tenantId == null) {
                throw new org.springframework.security.access.AccessDeniedException("Access denied");
            }

            String paymentStatus = normalizePaymentIntent(invoiceRequest.getStatus(), invoiceRequest.getPaymentStatus());
            if (paymentStatus == null || paymentStatus.isBlank()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid or empty paymentStatus value");
            }

            if (invoiceRequest.getItems() == null || invoiceRequest.getItems().isEmpty()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "At least one invoice item is required");
            }

            for (InvoiceItem item : invoiceRequest.getItems()) {
                if (item == null || item.getProductId() == null) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST, "Each invoice item requires a valid productId");
                }
                if (item.getQty() == null || item.getQty() <= 0) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST, "Each item qty must be greater than zero");
                }
                BigDecimal enteredPrice = item.getEnteredPrice() == null ? item.getPrice() : item.getEnteredPrice();
                if (enteredPrice == null || enteredPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST, "Each item price must be positive");
                }
            }

            String email = SecurityContextHolder.getContext().getAuthentication() != null
                    ? SecurityContextHolder.getContext().getAuthentication().getName()
                    : null;
            User currentUser = email == null || email.isBlank()
                    ? null
                    : userRepository.findByEmail(email)
                            .or(() -> userRepository.findByUsername(email))
                            .orElse(null);
            if (currentUser == null || currentUser.getTenant() == null
                    || currentUser.getTenant().getId() != Math.toIntExact(tenantId)) {
                throw new org.springframework.security.access.AccessDeniedException("Access denied");
            }
            if (idempotencyKey != null) {
                if (invoiceIdempotencyService == null) {
                    throw new IllegalStateException("Invoice idempotency service is not configured");
                }
                Optional<Invoice> replay = invoiceIdempotencyService.claimGstInvoice(
                        tenantId, idempotencyKey, invoiceRequest);
                if (replay.isPresent()) {
                    return ResponseEntity.ok()
                            .header("Idempotency-Replayed", "true")
                            .body(replay.get());
                }
            }
            InvoiceIssuerResolver.Snapshot issuer =
                    InvoiceIssuerResolver.resolve(currentUser, invoiceRequest.getIssuerType(), true);

            String invoicePricingMode = resolvePricingMode(invoiceRequest.getTaxPricingMode(), "invoice");
            String placeOfSupplyState = invoiceRequest.getPlaceOfSupplyState() == null
                    ? null
                    : invoiceRequest.getPlaceOfSupplyState().trim();
            if (placeOfSupplyState == null || placeOfSupplyState.isBlank()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "placeOfSupplyState is required");
            }
            boolean interState = isInterStateSupply(issuer.state(), issuer.gstin(), placeOfSupplyState);

            Map<Long, Product> productsById = new LinkedHashMap<>();
            for (InvoiceItem submittedItem : invoiceRequest.getItems()) {
                Product product = productRepository.findByIdAndTenantIdForUpdate(
                                submittedItem.getProductId(), tenantId)
                        .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                                org.springframework.http.HttpStatus.BAD_REQUEST,
                                "Product " + submittedItem.getProductId() + " not found for this tenant"));
                productsById.put(submittedItem.getProductId(), product);
            }
            Map<String, BigDecimal> ratesByHsn = hsnTaxRateService.ratesForProducts(productsById.values());

            List<InvoiceItem> invoiceItems = new java.util.ArrayList<>();
            for (InvoiceItem submittedItem : invoiceRequest.getItems()) {
                Product product = productsById.get(submittedItem.getProductId());
                String productName = product.getName() == null ? "Product " + product.getId() : product.getName();
                String hsn = HsnTaxRateService.normalizeCode(product.getHsnSac());
                String itemLabel = productName + " (product " + product.getId() + ")";
                if (!HsnTaxRateService.isValidCode(hsn)) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            itemLabel + " has a missing or invalid HSN/SAC code");
                }
                if (!hsn.equals(HsnTaxRateService.normalizeCode(submittedItem.getHsn()))) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            itemLabel + " HSN/SAC does not match the product record");
                }
                BigDecimal gstRate = ratesByHsn.get(hsn);
                if (gstRate == null) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            itemLabel + " has no applicable GST rate in the HSN/SAC tax master");
                }

                BigDecimal enteredPrice = submittedItem.getEnteredPrice() == null
                        ? submittedItem.getPrice()
                        : submittedItem.getEnteredPrice();
                BigDecimal catalogPrice = product.getPrice();
                if (catalogPrice == null || catalogPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            itemLabel + " has no valid catalogue price");
                }
                BigDecimal roundedCatalogPrice = (enteredPrice == null ? catalogPrice : enteredPrice)
                        .setScale(2, RoundingMode.HALF_UP);
                if (roundedCatalogPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            itemLabel + " price must be greater than zero");
                }

                String itemPricingMode = resolvePricingMode(submittedItem.getTaxPricingMode(), itemLabel);
                if (submittedItem.getTaxPricingMode() == null || submittedItem.getTaxPricingMode().isBlank()) {
                    itemPricingMode = invoicePricingMode;
                } else if (!invoicePricingMode.equals(itemPricingMode)) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            itemLabel + " taxPricingMode must match the invoice taxPricingMode");
                }
                BigDecimal lineGross;
                BigDecimal taxableAmount;
                BigDecimal taxAmount;
                if ("inclusive".equals(itemPricingMode)) {
                    lineGross = roundedCatalogPrice.multiply(BigDecimal.valueOf(submittedItem.getQty()))
                            .setScale(2, RoundingMode.HALF_UP);
                    taxableAmount = lineGross.divide(
                                    BigDecimal.ONE.add(gstRate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)),
                                    2,
                                    RoundingMode.HALF_UP)
                            .setScale(2, RoundingMode.HALF_UP);
                    taxAmount = lineGross.subtract(taxableAmount).setScale(2, RoundingMode.HALF_UP);
                } else {
                    taxableAmount = roundedCatalogPrice.multiply(BigDecimal.valueOf(submittedItem.getQty()))
                            .setScale(2, RoundingMode.HALF_UP);
                    taxAmount = taxableAmount.multiply(gstRate)
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    lineGross = taxableAmount.add(taxAmount).setScale(2, RoundingMode.HALF_UP);
                }

                BigDecimal cgst = BigDecimal.ZERO.setScale(2);
                BigDecimal sgst = BigDecimal.ZERO.setScale(2);
                BigDecimal igst = BigDecimal.ZERO.setScale(2);
                if (interState) {
                    igst = taxAmount;
                } else {
                    cgst = taxAmount.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                    sgst = taxAmount.subtract(cgst).setScale(2, RoundingMode.HALF_UP);
                }

                BigDecimal taxableUnitPrice = "inclusive".equals(itemPricingMode)
                        ? taxableAmount.divide(BigDecimal.valueOf(submittedItem.getQty()), 2, RoundingMode.HALF_UP)
                        : roundedCatalogPrice;
                InvoiceItem item = new InvoiceItem(
                        product.getId(),
                        productName,
                        product.getSku(),
                        hsn,
                        gstRate,
                        taxableUnitPrice,
                        submittedItem.getQty());
                item.setEnteredPrice(roundedCatalogPrice);
                item.setTaxPricingMode(itemPricingMode);
                item.setCalculatedAmounts(taxableAmount, taxAmount, lineGross);
                item.setTaxComponents(cgst, sgst, igst);
                invoiceItems.add(item);
            }

            String invoiceNo = InvoiceNumberGenerator.generate();

            for (InvoiceItem item : invoiceItems) {
                deductStockForSale(item, invoiceNo, tenantId);
            }

            BigDecimal subtotal = invoiceItems.stream()
                    .map(item -> item.getItemSubtotal() == null ? BigDecimal.ZERO : item.getItemSubtotal())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gstAmount = invoiceItems.stream()
                    .map(item -> item.getItemGst() == null ? BigDecimal.ZERO : item.getItemGst())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grandTotal = invoiceItems.stream()
                    .map(item -> item.getItemTotal() == null ? BigDecimal.ZERO : item.getItemTotal())
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal providedPayment = resolveRequestAmount(invoiceRequest);
            BigDecimal paidAmount = resolvePaidAmount(
                    grandTotal,
                    providedPayment,
                    invoiceRequest.getStatus(),
                    invoiceRequest.getPaymentStatus()
            );

            if (paidAmount.compareTo(BigDecimal.ZERO) < 0) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Paid amount cannot be negative");
            }
            if (grandTotal.compareTo(BigDecimal.ZERO) < 0) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Invoice total cannot be negative");
            }

            String clientStatusSummary = String.format("status=%s, paymentStatus=%s, paidAmount=%s",
                    invoiceRequest.getStatus(), invoiceRequest.getPaymentStatus(), providedPayment);
            log.info("Checkout payment intent for tenant {}: {}", tenantId, clientStatusSummary);

            Invoice invoice = new Invoice(invoiceNo, invoiceRequest.getCustomerName(),
                    invoiceRequest.getCustomerPhone(), LocalDate.now(),
                    invoiceRequest.getPaymentMethod(), subtotal, gstAmount, grandTotal,
                    invoiceItems);
            invoice.setPaidAmount(paidAmount);
            invoice.setTaxPricingMode(invoicePricingMode);
            invoice.setPlaceOfSupplyState(placeOfSupplyState);
            invoice.setCustomerAddress(sanitizeCustomerAddress(invoiceRequest.getCustomerAddress()));
            invoice.setPaymentStatus(resolvePaymentStatus(paidAmount, grandTotal));
            invoice.setStatus(resolveInvoiceStatus(invoice.getPaymentStatus()));
            invoice.setIssuerType(issuer.type());
            invoice.setIssuerName(issuer.name());
            invoice.setIssuerGstin(issuer.gstin());
            invoice.setIssuerAddress(issuer.address());
            invoice.setIssuerPhone(issuer.phone());
            invoice.setIssuerEmail(issuer.email());
            Tenant tenant = new Tenant();
            tenant.setId(Math.toIntExact(tenantId));
            invoice.setTenant(tenant);

            invoice.setUserId(currentUser.getId());

            for (InvoiceItem item : invoiceItems) {
                item.setInvoice(invoice);
            }

            Invoice saved = invoiceRepository.save(invoice);
            if (idempotencyKey != null) {
                invoiceIdempotencyService.completeGstInvoice(tenantId, idempotencyKey, saved.getId());
            }
            paymentCollectionService.recordInitialPayment(saved, currentUser);
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

    public ResponseEntity<?> createInvoice(InvoiceRequest invoiceRequest) {
        return createInvoice(invoiceRequest, null);
    }

    private BigDecimal resolveRequestAmount(InvoiceRequest invoiceRequest) {
        BigDecimal candidate = invoiceRequest.getPaidAmount();
        if (candidate == null || candidate.compareTo(BigDecimal.ZERO) < 0) {
            candidate = invoiceRequest.getAmountPaid();
        }
        if (candidate == null || candidate.compareTo(BigDecimal.ZERO) < 0) {
            candidate = invoiceRequest.getReceivedAmount();
        }
        if (candidate == null || candidate.compareTo(BigDecimal.ZERO) < 0) {
            candidate = BigDecimal.ZERO;
        }
        return candidate;
    }

    private String resolvePricingMode(String requestedMode, String label) {
        if (requestedMode == null || requestedMode.isBlank()) {
            return "exclusive";
        }
        String normalized = requestedMode.trim().toLowerCase(Locale.ROOT);
        if (!"exclusive".equals(normalized) && !"inclusive".equals(normalized)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    label + " taxPricingMode must be exclusive or inclusive");
        }
        return normalized;
    }

    private boolean isInterStateSupply(String sellerState, String sellerGstin, String placeOfSupplyState) {
        String buyerCode = resolveGstStateCode(placeOfSupplyState);
        if (buyerCode == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "placeOfSupplyState must be a valid Indian state name or GST state code");
        }
        String sellerCode;
        if (sellerGstin != null && sellerGstin.matches(
                "[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]")) {
            sellerCode = resolveGstStateCode(sellerGstin.substring(0, 2));
            if (sellerCode == null) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Seller GSTIN contains an invalid GST state code");
            }
        } else {
            sellerCode = resolveGstStateCode(sellerState);
        }
        if (sellerCode == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Seller state is not configured; cannot determine GST tax components");
        }
        return !sellerCode.equals(buyerCode);
    }

    private String resolveGstStateCode(String state) {
        return state == null ? null : GST_STATE_CODES.get(
                state.trim().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT));
    }

    private String sanitizeCustomerAddress(String address) {
        if (address == null) return null;
        String normalized = address.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 500) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Customer address must not exceed 500 characters");
        }
        return normalized;
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
            return grandTotal == null ? baseAmount : grandTotal.max(BigDecimal.ZERO);
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
        String candidate = paymentStatus != null && !paymentStatus.isBlank() ? paymentStatus : status;
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