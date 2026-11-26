package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.ExchangeOrderRequest;
import org.ib.ibbackend.entity.ExchangeOrder;
import org.ib.ibbackend.entity.InventoryMovement;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.ExchangeOrderRepository;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.ib.ibbackend.repository.ReturnOrderRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeOrderServiceTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldUseTenantInvoicePriceAndPersistExchangeType() {
        TenantContext.setCurrentTenant(1L);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        ReturnOrderRepository returnOrderRepository = mock(ReturnOrderRepository.class);
        ExchangeOrderRepository exchangeOrderRepository = mock(ExchangeOrderRepository.class);
        InventorySecurityService inventorySecurityService = mock(InventorySecurityService.class);
        UserRepository userRepository = mock(UserRepository.class);
        authenticateStoreAdmin(userRepository);

        Invoice invoice = new Invoice();
        invoice.setId(10L);
        invoice.setInvoiceNo("INV-2025/0001");
        invoice.setCustomerName("John");
        invoice.setCustomerPhone("9999999999");
        invoice.setDate(LocalDate.now());
        invoice.setStatus(Invoice.InvoiceStatus.PAID);
        Invoice.ItemStub soldItem = new Invoice.ItemStub();
        soldItem.setProductId(5L);
        soldItem.setName("Rice");
        soldItem.setQty(2);
        soldItem.setPrice(new BigDecimal("100.00"));
        invoice.setItems(List.of(soldItem.toInvoiceItem()));
        Tenant tenant = new Tenant();
        tenant.setId(1);
        invoice.setTenant(tenant);

        when(invoiceRepository.findByIdAndTenantIdForUpdate(10L, 1L)).thenReturn(Optional.of(invoice));
        when(returnOrderRepository.findReturnedQuantityTotals(10L, 1L, "gst")).thenReturn(List.of());
        when(exchangeOrderRepository.findExchangedQuantityTotals(10L, 1L, "gst")).thenReturn(List.of());
        when(exchangeOrderRepository.save(any(ExchangeOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SellOrderAdjustmentSupport support = new SellOrderAdjustmentSupport(invoiceRepository,
                mock(NonGstInvoiceRepository.class), returnOrderRepository, exchangeOrderRepository,
                inventorySecurityService, userRepository);
        ExchangeOrderService service = new ExchangeOrderService(support, exchangeOrderRepository);
        ExchangeOrderRequest request = new ExchangeOrderRequest();
        request.setInvoiceId(10L);
        request.setBillType("gst");
        request.setReason("Size exchange");
        request.setNewInvoiceAmount(new BigDecimal("50.00"));
        ExchangeOrderRequest.Item item = new ExchangeOrderRequest.Item();
        item.setProductId(5L);
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("1.00"));
        request.setItems(List.of(item));

        ExchangeOrder saved = service.createExchangeOrder(request);

        assertEquals("gst", saved.getBillType());
        assertEquals(new BigDecimal("100.00"), saved.getCreditAmount());
        assertEquals(new BigDecimal("50.00"), saved.getNewInvoiceAmount());
        verify(inventorySecurityService).applyMovement(anyLong(), anyInt(),
                org.mockito.ArgumentMatchers.eq(InventoryMovement.MovementType.EXCHANGE),
                anyString(), anyString(), anyString(), anyBoolean());
    }

    private void authenticateStoreAdmin(UserRepository userRepository) {
        User user = new User();
        user.setRole("store_admin");
        Tenant tenant = new Tenant();
        tenant.setId(1);
        user.setTenant(tenant);
        when(userRepository.findByEmail("admin@example.test")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.test", "token", List.of()));
    }
}
