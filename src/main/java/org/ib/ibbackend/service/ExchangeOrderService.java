package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.ExchangeOrderRequest;
import org.ib.ibbackend.entity.ExchangeOrder;
import org.ib.ibbackend.entity.ExchangeOrderItem;
import org.ib.ibbackend.entity.InventoryMovement;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.ExchangeOrderRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class ExchangeOrderService {

    private final InvoiceRepository invoiceRepository;
    private final ProductRepository productRepository;
    private final ExchangeOrderRepository exchangeOrderRepository;
    private final InventorySecurityService inventorySecurityService;

    public ExchangeOrderService(InvoiceRepository invoiceRepository,
                               ProductRepository productRepository,
                               ExchangeOrderRepository exchangeOrderRepository,
                               InventorySecurityService inventorySecurityService) {
        this.invoiceRepository = invoiceRepository;
        this.productRepository = productRepository;
        this.exchangeOrderRepository = exchangeOrderRepository;
        this.inventorySecurityService = inventorySecurityService;
    }

    @Transactional
    public ExchangeOrder createExchangeOrder(ExchangeOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Exchange order request is required");
        }
        if (request.getInvoiceId() == null) {
            throw new IllegalArgumentException("Invoice ID is required");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("At least one exchange item is required");
        }

        Invoice invoice = invoiceRepository.findById(request.getInvoiceId())
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + request.getInvoiceId()));

        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null && invoice.getTenant() != null) {
            tenantId = (long) invoice.getTenant().getId();
        }
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }

        ExchangeOrder exchangeOrder = new ExchangeOrder();
        exchangeOrder.setInvoiceId(invoice.getId());
        exchangeOrder.setInvoiceNo(invoice.getInvoiceNo());
        exchangeOrder.setCustomerName(invoice.getCustomerName());
        exchangeOrder.setCustomerPhone(invoice.getCustomerPhone());
        exchangeOrder.setReason(request.getReason());
        exchangeOrder.setTenant(invoice.getTenant());
        exchangeOrder.setNewInvoiceAmount(request.getNewInvoiceAmount());

        List<ExchangeOrderItem> exchangeItems = new ArrayList<>();
        BigDecimal creditTotal = BigDecimal.ZERO;

        for (ExchangeOrderRequest.Item itemRequest : request.getItems()) {
            if (itemRequest == null || itemRequest.getProductId() == null) {
                throw new IllegalArgumentException("Each exchange item must include a product ID");
            }
            if (itemRequest.getQuantity() == null || itemRequest.getQuantity() <= 0) {
                throw new IllegalArgumentException("Quantity must be greater than zero for product " + itemRequest.getProductId());
            }

            InvoiceItem invoiceItem = invoice.getItems() == null ? null : invoice.getItems().stream()
                    .filter(item -> item.getProductId() != null && item.getProductId().equals(itemRequest.getProductId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Product " + itemRequest.getProductId() + " is not present in invoice " + invoice.getInvoiceNo()));

            int soldQty = invoiceItem.getQty() == null ? 0 : invoiceItem.getQty();
            int requestedQty = itemRequest.getQuantity();
            if (requestedQty > soldQty) {
                throw new IllegalArgumentException("Cannot exchange more than sold quantity for product " + itemRequest.getProductId());
            }

            BigDecimal unitPrice = itemRequest.getUnitPrice() != null ? itemRequest.getUnitPrice() : (invoiceItem.getPrice() == null ? BigDecimal.ZERO : invoiceItem.getPrice());
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(requestedQty));
            creditTotal = creditTotal.add(lineTotal);

            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + itemRequest.getProductId()));

            if (inventorySecurityService != null) {
                inventorySecurityService.applyMovement(
                        itemRequest.getProductId(),
                        requestedQty,
                        InventoryMovement.MovementType.EXCHANGE,
                        "EXCHANGE_ORDER",
                        invoice.getInvoiceNo(),
                        "Stock returned for exchange order",
                        false
                );
            } else {
                int stock = product.getQuantity() == null ? 0 : product.getQuantity();
                product.setQuantity(stock + requestedQty);
                productRepository.save(product);
            }

            ExchangeOrderItem exchangeItem = new ExchangeOrderItem();
            exchangeItem.setProductId(itemRequest.getProductId());
            exchangeItem.setProductName(itemRequest.getProductName() != null ? itemRequest.getProductName() : invoiceItem.getName());
            exchangeItem.setQuantity(requestedQty);
            exchangeItem.setUnitPrice(unitPrice);
            exchangeItem.setLineTotal(lineTotal);
            exchangeItem.setExchangeOrder(exchangeOrder);
            exchangeItems.add(exchangeItem);
        }

        exchangeOrder.setItems(exchangeItems);
        exchangeOrder.setCreditAmount(creditTotal);
        return exchangeOrderRepository.save(exchangeOrder);
    }
}
