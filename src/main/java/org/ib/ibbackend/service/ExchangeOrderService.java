package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.ExchangeOrderRequest;
import org.ib.ibbackend.entity.ExchangeOrder;
import org.ib.ibbackend.entity.ExchangeOrderItem;
import org.ib.ibbackend.entity.InventoryMovement;
import org.ib.ibbackend.repository.ExchangeOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ExchangeOrderService {

    private final SellOrderAdjustmentSupport adjustmentSupport;
    private final ExchangeOrderRepository exchangeOrderRepository;

    public ExchangeOrderService(SellOrderAdjustmentSupport adjustmentSupport,
                                ExchangeOrderRepository exchangeOrderRepository) {
        this.adjustmentSupport = adjustmentSupport;
        this.exchangeOrderRepository = exchangeOrderRepository;
    }

    @Transactional
    public ExchangeOrder createExchangeOrder(ExchangeOrderRequest request) {
        if (request == null || request.getInvoiceId() == null) {
            throw new IllegalArgumentException("Invoice ID is required");
        }
        String reason = normalizeReason(request.getReason());
        BigDecimal newInvoiceAmount = request.getNewInvoiceAmount();
        if (newInvoiceAmount.signum() < 0 || newInvoiceAmount.stripTrailingZeros().scale() > 2
                || newInvoiceAmount.precision() > 12) {
            throw new IllegalArgumentException("New invoice amount must be non-negative with up to two decimal places");
        }

        SellOrderAdjustmentSupport.InvoiceSnapshot invoice =
                adjustmentSupport.lockInvoice(request.getInvoiceId(), request.getBillType());
        Map<Long, Integer> adjustedQuantities = adjustmentSupport.getAdjustedQuantities(invoice);
        Set<Long> seenProducts = new HashSet<>();
        List<ValidatedExchangeItem> validatedItems = new ArrayList<>();
        BigDecimal creditTotal = BigDecimal.ZERO;

        for (ExchangeOrderRequest.Item itemRequest : request.getItems()) {
            if (itemRequest == null || itemRequest.getProductId() == null) {
                throw new IllegalArgumentException("Each exchange item must include a product ID");
            }
            Long productId = itemRequest.getProductId();
            if (!seenProducts.add(productId)) {
                throw new IllegalArgumentException("A product can only be listed once per exchange");
            }
            if (itemRequest.getQuantity() == null || itemRequest.getQuantity() <= 0) {
                throw new IllegalArgumentException("Exchange quantity must be greater than zero for product " + productId);
            }

            SellOrderAdjustmentSupport.SoldItem soldItem = invoice.items().get(productId);
            if (soldItem == null) {
                throw new IllegalArgumentException("Product " + productId + " is not present in the sell order");
            }
            int remainingQuantity = soldItem.quantity() - adjustedQuantities.getOrDefault(productId, 0);
            if (itemRequest.getQuantity() > remainingQuantity) {
                throw new IllegalArgumentException("Only " + Math.max(remainingQuantity, 0)
                        + " unit(s) remain available to exchange for product " + productId);
            }

            BigDecimal unitPrice = soldItem.unitPrice();
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
            creditTotal = creditTotal.add(lineTotal);
            validatedItems.add(new ValidatedExchangeItem(productId, soldItem.productName(),
                    itemRequest.getQuantity(), unitPrice, lineTotal));
        }
        if (validatedItems.isEmpty()) {
            throw new IllegalArgumentException("At least one exchange item is required");
        }

        ExchangeOrder exchangeOrder = new ExchangeOrder();
        exchangeOrder.setInvoiceId(invoice.invoiceId());
        exchangeOrder.setBillType(invoice.billType());
        exchangeOrder.setInvoiceNo(invoice.invoiceNo());
        exchangeOrder.setCustomerName(invoice.customerName());
        exchangeOrder.setCustomerPhone(invoice.customerPhone());
        exchangeOrder.setReason(reason);
        exchangeOrder.setTenant(invoice.tenant());
        exchangeOrder.setNewInvoiceAmount(newInvoiceAmount);

        List<ExchangeOrderItem> exchangeItems = new ArrayList<>();
        for (ValidatedExchangeItem validatedItem : validatedItems) {
            adjustmentSupport.restoreStock(validatedItem.productId(), validatedItem.quantity(),
                    InventoryMovement.MovementType.EXCHANGE, "EXCHANGE_ORDER", invoice.invoiceNo(),
                    "Stock returned for exchange order");

            ExchangeOrderItem exchangeItem = new ExchangeOrderItem();
            exchangeItem.setProductId(validatedItem.productId());
            exchangeItem.setProductName(validatedItem.productName());
            exchangeItem.setQuantity(validatedItem.quantity());
            exchangeItem.setUnitPrice(validatedItem.unitPrice());
            exchangeItem.setLineTotal(validatedItem.lineTotal());
            exchangeItem.setExchangeOrder(exchangeOrder);
            exchangeItems.add(exchangeItem);
        }

        exchangeOrder.setItems(exchangeItems);
        exchangeOrder.setCreditAmount(creditTotal);
        return exchangeOrderRepository.save(exchangeOrder);
    }

    private String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new IllegalArgumentException("Exchange reason is required and must be 500 characters or fewer");
        }
        return normalized;
    }

    private record ValidatedExchangeItem(Long productId, String productName, int quantity,
                                         BigDecimal unitPrice, BigDecimal lineTotal) {
    }
}
