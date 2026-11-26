package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.ReturnOrderRequest;
import org.ib.ibbackend.entity.InventoryMovement;
import org.ib.ibbackend.entity.ReturnOrder;
import org.ib.ibbackend.entity.ReturnOrderItem;
import org.ib.ibbackend.repository.ReturnOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ReturnOrderService {

    private final SellOrderAdjustmentSupport adjustmentSupport;
    private final ReturnOrderRepository returnOrderRepository;

    public ReturnOrderService(SellOrderAdjustmentSupport adjustmentSupport,
                              ReturnOrderRepository returnOrderRepository) {
        this.adjustmentSupport = adjustmentSupport;
        this.returnOrderRepository = returnOrderRepository;
    }

    @Transactional
    public ReturnOrder createReturnOrder(ReturnOrderRequest request) {
        if (request == null || request.getInvoiceId() == null) {
            throw new IllegalArgumentException("Invoice ID is required");
        }
        String reason = normalizeReason(request.getReason());
        SellOrderAdjustmentSupport.InvoiceSnapshot invoice =
                adjustmentSupport.lockInvoice(request.getInvoiceId(), request.getBillType());
        Map<Long, Integer> adjustedQuantities = adjustmentSupport.getAdjustedQuantities(invoice);
        Set<Long> seenProducts = new HashSet<>();
        List<ValidatedReturnItem> validatedItems = new ArrayList<>();
        BigDecimal totalReturned = BigDecimal.ZERO;

        for (ReturnOrderRequest.Item itemRequest : request.getItems()) {
            if (itemRequest == null || itemRequest.getProductId() == null) {
                throw new IllegalArgumentException("Each return item must include a product ID");
            }
            Long productId = itemRequest.getProductId();
            if (!seenProducts.add(productId)) {
                throw new IllegalArgumentException("A product can only be listed once per return");
            }
            if (itemRequest.getQuantity() == null || itemRequest.getQuantity() <= 0) {
                throw new IllegalArgumentException("Return quantity must be greater than zero for product " + productId);
            }

            SellOrderAdjustmentSupport.SoldItem soldItem = invoice.items().get(productId);
            if (soldItem == null) {
                throw new IllegalArgumentException("Product " + productId + " is not present in the sell order");
            }
            int remainingQuantity = soldItem.quantity() - adjustedQuantities.getOrDefault(productId, 0);
            if (itemRequest.getQuantity() > remainingQuantity) {
                throw new IllegalArgumentException("Only " + Math.max(remainingQuantity, 0)
                        + " unit(s) remain available to return for product " + productId);
            }

            BigDecimal unitPrice = soldItem.unitPrice();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
            totalReturned = totalReturned.add(lineTotal);
            validatedItems.add(new ValidatedReturnItem(productId, soldItem.productName(),
                    itemRequest.getQuantity(), unitPrice, lineTotal));
        }
        if (validatedItems.isEmpty()) {
            throw new IllegalArgumentException("At least one return item is required");
        }

        ReturnOrder returnOrder = new ReturnOrder();
        returnOrder.setInvoiceId(invoice.invoiceId());
        returnOrder.setBillType(invoice.billType());
        returnOrder.setInvoiceNo(invoice.invoiceNo());
        returnOrder.setCustomerName(invoice.customerName());
        returnOrder.setCustomerPhone(invoice.customerPhone());
        returnOrder.setReason(reason);
        returnOrder.setTenant(invoice.tenant());

        List<ReturnOrderItem> returnItems = new ArrayList<>();
        for (ValidatedReturnItem validatedItem : validatedItems) {
            adjustmentSupport.restoreStock(validatedItem.productId(), validatedItem.quantity(),
                    InventoryMovement.MovementType.RETURN, "RETURN_ORDER", invoice.invoiceNo(),
                    "Stock restored for return order");

            ReturnOrderItem returnItem = new ReturnOrderItem();
            returnItem.setProductId(validatedItem.productId());
            returnItem.setProductName(validatedItem.productName());
            returnItem.setQuantity(validatedItem.quantity());
            returnItem.setUnitPrice(validatedItem.unitPrice());
            returnItem.setLineTotal(validatedItem.lineTotal());
            returnItem.setReturnOrder(returnOrder);
            returnItems.add(returnItem);
        }

        returnOrder.setItems(returnItems);
        returnOrder.setReturnedAmount(totalReturned);
        return returnOrderRepository.save(returnOrder);
    }

    @Transactional(readOnly = true)
    public List<AvailableItem> getAvailableItems(Long invoiceId, String billType) {
        SellOrderAdjustmentSupport.InvoiceSnapshot invoice = adjustmentSupport.readInvoice(invoiceId, billType);
        Map<Long, Integer> adjustedQuantities = adjustmentSupport.getAdjustedQuantities(invoice);
        return invoice.items().values().stream()
                .map(item -> new AvailableItem(
                        item.productId(),
                        item.productName(),
                        item.quantity(),
                        Math.max(item.quantity() - adjustedQuantities.getOrDefault(item.productId(), 0), 0),
                        item.unitPrice()))
                .filter(item -> item.availableQuantity() > 0)
                .toList();
    }

    private String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new IllegalArgumentException("Return reason is required and must be 500 characters or fewer");
        }
        return normalized;
    }

    private record ValidatedReturnItem(Long productId, String productName, int quantity,
                                       BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public record AvailableItem(Long productId, String productName, int soldQuantity, int availableQuantity,
                                BigDecimal unitPrice) {
    }
}
