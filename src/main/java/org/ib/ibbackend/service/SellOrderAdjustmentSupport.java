package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.InventoryMovement;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.NonGstInvoice;
import org.ib.ibbackend.entity.NonGstInvoiceItem;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.ExchangeOrderRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.ib.ibbackend.repository.ReturnOrderRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class SellOrderAdjustmentSupport {

    private final InvoiceRepository invoiceRepository;
    private final NonGstInvoiceRepository nonGstInvoiceRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final ExchangeOrderRepository exchangeOrderRepository;
    private final InventorySecurityService inventorySecurityService;
    private final UserRepository userRepository;

    public SellOrderAdjustmentSupport(InvoiceRepository invoiceRepository,
                                     NonGstInvoiceRepository nonGstInvoiceRepository,
                                     ReturnOrderRepository returnOrderRepository,
                                     ExchangeOrderRepository exchangeOrderRepository,
                                     InventorySecurityService inventorySecurityService,
                                     UserRepository userRepository) {
        this.invoiceRepository = invoiceRepository;
        this.nonGstInvoiceRepository = nonGstInvoiceRepository;
        this.returnOrderRepository = returnOrderRepository;
        this.exchangeOrderRepository = exchangeOrderRepository;
        this.inventorySecurityService = inventorySecurityService;
        this.userRepository = userRepository;
    }

    public InvoiceSnapshot lockInvoice(Long invoiceId, String requestedBillType) {
        return loadInvoice(invoiceId, requestedBillType, true);
    }

    public InvoiceSnapshot readInvoice(Long invoiceId, String requestedBillType) {
        return loadInvoice(invoiceId, requestedBillType, false);
    }

    private InvoiceSnapshot loadInvoice(Long invoiceId, String requestedBillType, boolean lock) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        requireBillingWriter(tenantId);
        String billType = normalizeBillType(requestedBillType);

        if ("non-gst".equals(billType)) {
            Optional<NonGstInvoice> invoiceResult = lock
                    ? nonGstInvoiceRepository.findByIdAndTenantIdForUpdate(invoiceId, tenantId)
                    : nonGstInvoiceRepository.findByIdAndTenantId(invoiceId, tenantId);
            NonGstInvoice invoice = invoiceResult
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sell order not found"));
            if ("CANCELLED".equalsIgnoreCase(invoice.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancelled sell orders cannot be returned or exchanged");
            }

            Map<Long, SoldItem> items = new LinkedHashMap<>();
            if (invoice.getItems() != null) {
                for (NonGstInvoiceItem item : invoice.getItems()) {
                    if (item.getProduct() != null) {
                        addItem(items, item.getProduct().getId(), item.getName(), item.getQty(), item.getPrice());
                    }
                }
            }
            return new InvoiceSnapshot(invoice.getId(), invoice.getInvoiceNo(), invoice.getCustomerName(),
                    invoice.getCustomerPhone(), invoice.getTenant(), billType, items);
        }

        Optional<Invoice> invoiceResult = lock
                ? invoiceRepository.findByIdAndTenantIdForUpdate(invoiceId, tenantId)
                : invoiceRepository.findByIdAndTenantId(invoiceId, tenantId);
        Invoice invoice = invoiceResult
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sell order not found"));
        if (invoice.getStatus() == Invoice.InvoiceStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancelled sell orders cannot be returned or exchanged");
        }

        Map<Long, SoldItem> items = new LinkedHashMap<>();
        if (invoice.getItems() != null) {
            for (InvoiceItem item : invoice.getItems()) {
                addItem(items, item.getProductId(), item.getName(), item.getQty(), item.getPrice());
            }
        }
        return new InvoiceSnapshot(invoice.getId(), invoice.getInvoiceNo(), invoice.getCustomerName(),
                invoice.getCustomerPhone(), invoice.getTenant(), billType, items);
    }

    public Map<Long, Integer> getAdjustedQuantities(InvoiceSnapshot invoice) {
        Long tenantId = TenantContext.getCurrentTenant();
        Map<Long, Integer> quantities = new HashMap<>();
        addQuantities(quantities, returnOrderRepository.findReturnedQuantityTotals(
                invoice.invoiceId(), tenantId, invoice.billType()));
        addQuantities(quantities, exchangeOrderRepository.findExchangedQuantityTotals(
                invoice.invoiceId(), tenantId, invoice.billType()));
        return quantities;
    }

    public void assertCanCancel(Long invoiceId, Long tenantId, String billType) {
        requireBillingWriter(tenantId);
        String normalizedBillType = normalizeBillType(billType);
        if (returnOrderRepository.existsByTenant_IdAndBillTypeAndInvoiceId(tenantId, normalizedBillType, invoiceId)
                || exchangeOrderRepository.existsByTenant_IdAndBillTypeAndInvoiceId(tenantId, normalizedBillType, invoiceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This sell order has a return or exchange recorded and cannot be cancelled");
        }
    }

    public void restoreStock(Long productId, int quantity, InventoryMovement.MovementType movementType,
                             String referenceType, String invoiceNo, String note) {
        inventorySecurityService.applyMovement(productId, quantity, movementType, referenceType, invoiceNo, note, false);
    }

    private void requireBillingWriter(Long tenantId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AccessDeniedException("Authentication required");
        }

        User user = userRepository.findByEmail(authentication.getName())
                .or(() -> userRepository.findByUsername(authentication.getName()))
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (user.getTenant() == null || !Integer.valueOf(Math.toIntExact(tenantId)).equals(user.getTenant().getId())
                || user.isDisabled() || UserRolePolicy.isDisabledRole(user.getRole())
                || !UserRolePolicy.canManageBilling(user.getRole())) {
            throw new AccessDeniedException("Billing order changes are not permitted");
        }
    }

    public static String normalizeBillType(String billType) {
        String normalized = billType == null || billType.isBlank()
                ? "gst"
                : billType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "gst" -> "gst";
            case "non-gst", "non gst", "non_gst" -> "non-gst";
            default -> throw new IllegalArgumentException("Bill type must be GST or non-GST");
        };
    }

    private static void addItem(Map<Long, SoldItem> items, Long productId, String name, Integer quantity, BigDecimal unitPrice) {
        if (productId == null || quantity == null || quantity <= 0) {
            return;
        }
        BigDecimal safePrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        SoldItem existing = items.get(productId);
        if (existing == null) {
            items.put(productId, new SoldItem(productId, name, quantity, safePrice.multiply(BigDecimal.valueOf(quantity))));
        } else {
            existing.add(name, quantity, safePrice.multiply(BigDecimal.valueOf(quantity)));
        }
    }

    private static void addQuantities(Map<Long, Integer> quantities, List<Object[]> rows) {
        for (Object[] row : rows) {
            if (row[0] instanceof Number productId && row[1] instanceof Number quantity) {
                quantities.merge(productId.longValue(), quantity.intValue(), Integer::sum);
            }
        }
    }

    public record InvoiceSnapshot(Long invoiceId, String invoiceNo, String customerName, String customerPhone,
                                  Tenant tenant, String billType, Map<Long, SoldItem> items) {
    }

    public static final class SoldItem {
        private final Long productId;
        private String productName;
        private int quantity;
        private BigDecimal totalValue;

        private SoldItem(Long productId, String productName, int quantity, BigDecimal totalValue) {
            this.productId = productId;
            this.productName = productName;
            this.quantity = quantity;
            this.totalValue = totalValue;
        }

        private void add(String name, int addedQuantity, BigDecimal addedValue) {
            if (productName == null || productName.isBlank()) {
                productName = name;
            }
            quantity += addedQuantity;
            totalValue = totalValue.add(addedValue);
        }

        public Long productId() {
            return productId;
        }

        public String productName() {
            return productName;
        }

        public int quantity() {
            return quantity;
        }

        public BigDecimal unitPrice() {
            return totalValue.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
        }
    }
}
