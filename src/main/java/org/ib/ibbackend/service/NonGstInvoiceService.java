package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.NonGstInvoiceRequest;
import org.ib.ibbackend.dto.NonGstInvoiceRequestItem;
import org.ib.ibbackend.entity.NonGstInvoice;
import org.ib.ibbackend.entity.NonGstInvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class NonGstInvoiceService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_SEARCH_LENGTH = 100;
    private static final int MAX_ITEMS_PER_INVOICE = 500;

    private final NonGstInvoiceRepository nonGstInvoiceRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final SequenceService sequenceService;

    public NonGstInvoiceService(
            NonGstInvoiceRepository nonGstInvoiceRepository,
            ProductRepository productRepository,
            UserRepository userRepository,
            SequenceService sequenceService
    ) {
        this.nonGstInvoiceRepository = nonGstInvoiceRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.sequenceService = sequenceService;
    }

    @Transactional
    public NonGstInvoice createNonGstInvoice(NonGstInvoiceRequest request) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        User currentUser = resolveCurrentUser();
        if (currentUser.getTenant() == null || currentUser.getTenant().getId() != Math.toIntExact(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated user does not belong to current tenant");
        }

        validateRequest(request);

        String invoiceNo = String.format("INV-%d/%04d", java.time.Year.now().getValue(), sequenceService.nextValue("non_gst_invoice"));

        NonGstInvoice invoice = new NonGstInvoice();
        invoice.setInvoiceNo(invoiceNo);
        invoice.setCustomerName(sanitizeText(request.getCustomerName(), 255));
        invoice.setCustomerPhone(sanitizeText(request.getCustomerPhone(), 50));
        invoice.setDate(LocalDate.now());
        invoice.setPaymentMethod(sanitizeText(request.getPaymentMethod(), 100));
        invoice.setBillType("non-gst");
        invoice.setGstInvoice(false);
        invoice.setPaymentStatus(normalizeStatus(request.getPaymentStatus()));
        invoice.setStatus(normalizeStatus(request.getStatus()));

        Tenant tenantRef = new Tenant();
        tenantRef.setId(Math.toIntExact(tenantId));
        invoice.setTenant(tenantRef);
        invoice.setUser(currentUser);

        List<NonGstInvoiceItem> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (NonGstInvoiceRequestItem requestItem : request.getItems()) {
            Product product = productRepository.findByIdAndTenantIdForUpdate(requestItem.getProductId(), tenantId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found for tenant: " + requestItem.getProductId()));

            int qty = requestItem.getQty() == null ? 0 : requestItem.getQty();
            if (qty <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Qty must be greater than zero");
            }

            int remaining = (product.getQuantity() == null ? 0 : product.getQuantity()) - qty;
            if (remaining < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient stock for " + product.getName());
            }

            BigDecimal price = requestItem.getPrice() == null ? BigDecimal.ZERO : requestItem.getPrice();
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item price must be positive");
            }

            NonGstInvoiceItem item = new NonGstInvoiceItem();
            item.setInvoice(invoice);
            item.setProduct(product);
                item.setName(sanitizeText(requestItem.getName(), 255) != null ? sanitizeText(requestItem.getName(), 255) : product.getName());
                item.setSku(product.getSku());
                item.setHsn(product.getHsnSac());
                item.setCategory(sanitizeText(requestItem.getCategory(), 100) != null
                    ? sanitizeText(requestItem.getCategory(), 100)
                    : product.getCategory());
            item.setGstRate(0);
            item.setCgst(BigDecimal.ZERO);
            item.setSgst(BigDecimal.ZERO);
            item.setIgst(BigDecimal.ZERO);
            item.setTaxAmount(BigDecimal.ZERO);
            item.setPrice(price.setScale(2, RoundingMode.HALF_UP));
            item.setQty(qty);

            BigDecimal itemSubtotal = item.getPrice().multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
            item.setItemSubtotal(itemSubtotal);
            item.setItemTotal(itemSubtotal);
            items.add(item);

            subtotal = subtotal.add(itemSubtotal);
            product.setQuantity(remaining);
            productRepository.save(product);
        }

        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        invoice.setSubtotal(subtotal);
        invoice.setGstAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        invoice.setGrandTotal(subtotal);

        BigDecimal paidAmount = request.getPaidAmount() == null ? BigDecimal.ZERO : request.getPaidAmount().max(BigDecimal.ZERO);
        if ("PAID".equals(invoice.getPaymentStatus())) {
            paidAmount = invoice.getGrandTotal();
        }
        if (paidAmount.compareTo(invoice.getGrandTotal()) > 0) {
            paidAmount = invoice.getGrandTotal();
        }
        invoice.setPaidAmount(paidAmount.setScale(2, RoundingMode.HALF_UP));

        invoice.setItems(items);
        return nonGstInvoiceRepository.save(invoice);
    }

    public Page<Map<String, Object>> listNonGstInvoices(int page, int size, String search, String billType) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        String safeSearch = search == null ? "" : search.trim();
        if (safeSearch.length() > MAX_SEARCH_LENGTH) {
            safeSearch = safeSearch.substring(0, MAX_SEARCH_LENGTH);
        }

        if (billType != null && !billType.isBlank() && !"non-gst".equalsIgnoreCase(billType.trim())) {
            return Page.empty();
        }

        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by("id").descending());
        Page<NonGstInvoice> pageData;
        if (!safeSearch.isBlank()) {
            pageData = nonGstInvoiceRepository.findBySearchAndTenantId(tenantId, safeSearch, pageable);
        } else {
            pageData = nonGstInvoiceRepository.findByTenantId(tenantId, pageable);
        }

        return pageData.map(invoice -> {
            Map<String, Object> data = new HashMap<>();
            data.put("id", invoice.getId());
            data.put("invoiceNo", invoice.getInvoiceNo());
            data.put("date", invoice.getDate());
            data.put("customerName", invoice.getCustomerName());
            data.put("customerPhone", invoice.getCustomerPhone());
            data.put("paymentMethod", invoice.getPaymentMethod());
            data.put("subtotal", invoice.getSubtotal());
            data.put("gstAmount", BigDecimal.ZERO);
            data.put("grandTotal", invoice.getGrandTotal());
            data.put("totalAmount", invoice.getGrandTotal());
            data.put("paidAmount", invoice.getPaidAmount());
            data.put("paymentStatus", invoice.getPaymentStatus());
            data.put("status", invoice.getStatus());
            data.put("billType", "non-gst");
            data.put("isGstInvoice", false);
            data.put("tenantId", invoice.getTenant() != null ? invoice.getTenant().getId() : null);
            data.put("userId", invoice.getUser() != null ? invoice.getUser().getId() : null);
            data.put("items", invoice.getItems() == null ? List.of() : invoice.getItems().stream().map(item -> {
                Map<String, Object> i = new HashMap<>();
                i.put("id", item.getId());
                i.put("productId", item.getProduct() != null ? item.getProduct().getId() : null);
                i.put("name", item.getName());
                i.put("sku", item.getSku());
                i.put("hsn", item.getHsn());
                i.put("category", item.getCategory());
                i.put("gstRate", item.getGstRate());
                i.put("cgst", item.getCgst());
                i.put("sgst", item.getSgst());
                i.put("igst", item.getIgst());
                i.put("taxAmount", item.getTaxAmount());
                i.put("price", item.getPrice());
                i.put("qty", item.getQty());
                i.put("itemSubtotal", item.getItemSubtotal());
                i.put("itemTotal", item.getItemTotal());
                return i;
            }).toList());
            return data;
        });
    }

    private User resolveCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : null;

        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found");
        }

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
    }

    private void validateRequest(NonGstInvoiceRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        if (request.getCustomerName() == null || request.getCustomerName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerName is required");
        }
        if (request.getCustomerName().trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerName is too long");
        }
        if (request.getCustomerPhone() != null && request.getCustomerPhone().length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "customerPhone is too long");
        }
        if (request.getPaymentMethod() != null && request.getPaymentMethod().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentMethod is too long");
        }
        if (request.getBillType() == null || !"non-gst".equalsIgnoreCase(request.getBillType().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "billType must be non-gst");
        }
        if (request.getIsGstInvoice() == null || request.getIsGstInvoice()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "isGstInvoice must be false");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one item is required");
        }
        if (request.getItems().size() > MAX_ITEMS_PER_INVOICE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many invoice items");
        }
        if (!isSupportedStatus(request.getPaymentStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid paymentStatus value");
        }
        if (!isSupportedStatus(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status value");
        }

        for (NonGstInvoiceRequestItem item : request.getItems()) {
            if (item.getProductId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "productId is required for each item");
            }
            if (item.getQty() == null || item.getQty() <= 0 || item.getQty() > 1_000_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "qty must be between 1 and 1000000");
            }
            if (item.getPrice() == null || item.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "price must be positive");
            }
            if (!isZero(item.getTaxAmount()) || !isZero(item.getCgst()) || !isZero(item.getSgst()) || !isZero(item.getIgst())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taxAmount, cgst, sgst, igst must be 0 for non-gst items");
            }
            Integer gstRate = item.getGstRate() == null ? 0 : item.getGstRate();
            if (gstRate != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gstRate must be 0 for non-gst items");
            }
        }
    }

    private boolean isZero(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) == 0;
    }

    private String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return "PENDING";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("SUCCESS".equals(normalized) || "COMPLETED".equals(normalized)) {
            return "PAID";
        }
        if ("PARTIALLY_PAID".equals(normalized) || "PARTIALLYPAID".equals(normalized)) {
            return "PARTIAL";
        }
        return normalized;
    }

    private boolean isSupportedStatus(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = normalizeStatus(value);
        return "PENDING".equals(normalized)
                || "PAID".equals(normalized)
                || "PARTIAL".equals(normalized)
                || "CANCELLED".equals(normalized);
    }

    private String sanitizeText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = value.trim().replaceAll("[\\r\\n\\t]", " ");
        if (sanitized.isEmpty()) {
            return null;
        }
        if (sanitized.length() > maxLength) {
            return sanitized.substring(0, maxLength);
        }
        return sanitized;
    }
}
