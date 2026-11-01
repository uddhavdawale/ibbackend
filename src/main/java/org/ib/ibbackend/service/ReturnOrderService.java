package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.ReturnOrderRequest;
import org.ib.ibbackend.entity.InventoryMovement;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.ReturnOrder;
import org.ib.ibbackend.entity.ReturnOrderItem;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.ReturnOrderRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReturnOrderService {

    private final InvoiceRepository invoiceRepository;
    private final ProductRepository productRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final InventorySecurityService inventorySecurityService;

    public ReturnOrderService(InvoiceRepository invoiceRepository,
                              ProductRepository productRepository,
                              ReturnOrderRepository returnOrderRepository,
                              InventorySecurityService inventorySecurityService) {
        this.invoiceRepository = invoiceRepository;
        this.productRepository = productRepository;
        this.returnOrderRepository = returnOrderRepository;
        this.inventorySecurityService = inventorySecurityService;
    }

    @Transactional
    public ReturnOrder createReturnOrder(ReturnOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Return order request is required");
        }
        if (request.getInvoiceId() == null) {
            throw new IllegalArgumentException("Invoice ID is required");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("At least one return item is required");
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

        ReturnOrder returnOrder = new ReturnOrder();
        returnOrder.setInvoiceId(invoice.getId());
        returnOrder.setInvoiceNo(invoice.getInvoiceNo());
        returnOrder.setCustomerName(invoice.getCustomerName());
        returnOrder.setCustomerPhone(invoice.getCustomerPhone());
        returnOrder.setReason(request.getReason());
        returnOrder.setTenant(invoice.getTenant());

        List<ReturnOrderItem> returnItems = new ArrayList<>();
        BigDecimal totalReturned = BigDecimal.ZERO;

        for (ReturnOrderRequest.Item itemRequest : request.getItems()) {
            if (itemRequest == null || itemRequest.getProductId() == null) {
                throw new IllegalArgumentException("Each return item must include a product ID");
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
                throw new IllegalArgumentException("Cannot return more than sold quantity for product " + itemRequest.getProductId());
            }

            BigDecimal unitPrice = itemRequest.getUnitPrice() != null ? itemRequest.getUnitPrice() : (invoiceItem.getPrice() == null ? BigDecimal.ZERO : invoiceItem.getPrice());
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(requestedQty));
            totalReturned = totalReturned.add(lineTotal);

            ReturnOrderItem returnItem = new ReturnOrderItem();
            returnItem.setProductId(itemRequest.getProductId());
            returnItem.setProductName(itemRequest.getProductName() != null ? itemRequest.getProductName() : invoiceItem.getName());
            returnItem.setQuantity(requestedQty);
            returnItem.setUnitPrice(unitPrice);
            returnItem.setLineTotal(lineTotal);
            returnItem.setReturnOrder(returnOrder);
            returnItems.add(returnItem);

            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + itemRequest.getProductId()));

            if (inventorySecurityService != null) {
                inventorySecurityService.applyMovement(
                        itemRequest.getProductId(),
                        requestedQty,
                        InventoryMovement.MovementType.RETURN,
                        "RETURN_ORDER",
                        invoice.getInvoiceNo(),
                        "Stock restored for return order",
                        false
                );
            } else {
                int stock = product.getQuantity() == null ? 0 : product.getQuantity();
                product.setQuantity(stock + requestedQty);
                productRepository.save(product);
            }
        }

        returnOrder.setItems(returnItems);
        returnOrder.setReturnedAmount(totalReturned);
        return returnOrderRepository.save(returnOrder);
    }
}
