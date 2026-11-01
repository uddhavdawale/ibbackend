package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.ReturnOrderRequest;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.ReturnOrder;
import org.ib.ibbackend.entity.ReturnOrderItem;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.ReturnOrderRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReturnOrderServiceTest {

    @Test
    void shouldCreateReturnOrderWithCalculatedAmount() {
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        ReturnOrderRepository returnOrderRepository = mock(ReturnOrderRepository.class);
        InventorySecurityService inventorySecurityService = mock(InventorySecurityService.class);

        Invoice invoice = new Invoice();
        invoice.setId(10L);
        invoice.setInvoiceNo("INV-2025/0001");
        invoice.setCustomerName("John");
        invoice.setCustomerPhone("9999999999");
        invoice.setDate(LocalDate.now());
        invoice.setGrandTotal(new BigDecimal("2000"));
        invoice.setPaidAmount(new BigDecimal("2000"));
        invoice.setStatus(Invoice.InvoiceStatus.PAID);

        Invoice.ItemStub item = new Invoice.ItemStub();
        item.setProductId(5L);
        item.setName("Rice");
        item.setQty(2);
        item.setPrice(new BigDecimal("100"));
        item.setGstRate(5);

        invoice.setItems(List.of(item.toInvoiceItem()));
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        invoice.setTenant(tenant);

        when(invoiceRepository.findById(10L)).thenReturn(Optional.of(invoice));
        when(returnOrderRepository.save(any(ReturnOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnOrderService service = new ReturnOrderService(invoiceRepository, productRepository, returnOrderRepository, inventorySecurityService);
        ReturnOrderRequest request = new ReturnOrderRequest();
        request.setInvoiceId(10L);
        request.setReason("Damaged item");

        ReturnOrderRequest.Item itemRequest = new ReturnOrderRequest.Item();
        itemRequest.setProductId(5L);
        itemRequest.setQuantity(1);
        itemRequest.setProductName("Rice");
        itemRequest.setUnitPrice(new BigDecimal("100"));
        request.setItems(List.of(itemRequest));

        ReturnOrder saved = service.createReturnOrder(request);

        assertNotNull(saved);
        assertEquals("INV-2025/0001", saved.getInvoiceNo());
        assertEquals(new BigDecimal("100.00"), saved.getReturnedAmount());
        assertEquals("Damaged item", saved.getReason());
        assertEquals(1, saved.getItems().size());
    }
}
