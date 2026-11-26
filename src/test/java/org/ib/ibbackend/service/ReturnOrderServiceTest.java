package org.ib.ibbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ib.ibbackend.dto.ReturnOrderRequest;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.InventoryMovement;
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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReturnOrderServiceTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAcceptFrontendReturnItemsAlias() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String json = "{\"invoiceId\":10,\"reason\":\"Damaged item\",\"returnItems\":[{\"productId\":5,\"productName\":\"Rice\",\"quantity\":1,\"unitPrice\":100.00}]}";

        ReturnOrderRequest request = objectMapper.readValue(json, ReturnOrderRequest.class);

        assertNotNull(request);
        assertEquals(1, request.getItems().size());
        assertEquals(5L, request.getItems().get(0).getProductId());
    }

    @Test
    void shouldScopeInvoiceAndCalculateReturnValueFromOriginalSalePrice() {
        TenantContext.setCurrentTenant(1L);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        ReturnOrderRepository returnOrderRepository = mock(ReturnOrderRepository.class);
        ExchangeOrderRepository exchangeOrderRepository = mock(ExchangeOrderRepository.class);
        InventorySecurityService inventorySecurityService = mock(InventorySecurityService.class);
        UserRepository userRepository = mock(UserRepository.class);
        authenticateStoreAdmin(userRepository);
        Invoice invoice = createInvoice();

        when(invoiceRepository.findByIdAndTenantIdForUpdate(10L, 1L)).thenReturn(Optional.of(invoice));
        when(returnOrderRepository.findReturnedQuantityTotals(10L, 1L, "gst")).thenReturn(List.of());
        when(exchangeOrderRepository.findExchangedQuantityTotals(10L, 1L, "gst")).thenReturn(List.of());
        when(returnOrderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnOrderService service = createService(invoiceRepository, returnOrderRepository, exchangeOrderRepository, inventorySecurityService, userRepository);
        ReturnOrderRequest request = createRequest(1, new BigDecimal("1.00"));

        var saved = service.createReturnOrder(request);

        assertEquals("INV-2025/0001", saved.getInvoiceNo());
        assertEquals(new BigDecimal("100.00"), saved.getReturnedAmount());
        assertEquals("Damaged item", saved.getReason());
        assertEquals("gst", saved.getBillType());
        assertEquals(1, saved.getItems().size());
        verify(invoiceRepository).findByIdAndTenantIdForUpdate(10L, 1L);
        verify(inventorySecurityService).applyMovement(anyLong(), anyInt(), any(InventoryMovement.MovementType.class),
                anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void shouldRejectQuantitiesAlreadyExchanged() {
        TenantContext.setCurrentTenant(1L);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        ReturnOrderRepository returnOrderRepository = mock(ReturnOrderRepository.class);
        ExchangeOrderRepository exchangeOrderRepository = mock(ExchangeOrderRepository.class);
        InventorySecurityService inventorySecurityService = mock(InventorySecurityService.class);
        UserRepository userRepository = mock(UserRepository.class);
        authenticateStoreAdmin(userRepository);
        when(invoiceRepository.findByIdAndTenantIdForUpdate(10L, 1L)).thenReturn(Optional.of(createInvoice()));
        when(returnOrderRepository.findReturnedQuantityTotals(10L, 1L, "gst")).thenReturn(List.of());
        when(exchangeOrderRepository.findExchangedQuantityTotals(10L, 1L, "gst"))
                .thenReturn(List.<Object[]>of(new Object[]{5L, 1L}));

        ReturnOrderService service = createService(invoiceRepository, returnOrderRepository, exchangeOrderRepository, inventorySecurityService, userRepository);

        assertThrows(IllegalArgumentException.class, () -> service.createReturnOrder(createRequest(2, null)));
    }

    @Test
    void shouldReportOnlyRemainingReturnQuantities() {
        TenantContext.setCurrentTenant(1L);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        ReturnOrderRepository returnOrderRepository = mock(ReturnOrderRepository.class);
        ExchangeOrderRepository exchangeOrderRepository = mock(ExchangeOrderRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        authenticateStoreAdmin(userRepository);
        when(invoiceRepository.findByIdAndTenantId(10L, 1L)).thenReturn(Optional.of(createInvoice()));
        when(returnOrderRepository.findReturnedQuantityTotals(10L, 1L, "gst"))
                .thenReturn(List.<Object[]>of(new Object[]{5L, 1L}));
        when(exchangeOrderRepository.findExchangedQuantityTotals(10L, 1L, "gst"))
                .thenReturn(List.<Object[]>of(new Object[]{5L, 0L}));

        ReturnOrderService service = createService(
                invoiceRepository, returnOrderRepository, exchangeOrderRepository,
                mock(InventorySecurityService.class), userRepository);

        var availableItems = service.getAvailableItems(10L, "gst");

        assertEquals(1, availableItems.size());
        assertEquals(1, availableItems.get(0).availableQuantity());
    }

    @Test
    void shouldNotLoadInvoiceOutsideCurrentTenant() {
        TenantContext.setCurrentTenant(1L);
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        ReturnOrderRepository returnOrderRepository = mock(ReturnOrderRepository.class);
        ExchangeOrderRepository exchangeOrderRepository = mock(ExchangeOrderRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        authenticateStoreAdmin(userRepository);
        when(invoiceRepository.findByIdAndTenantIdForUpdate(10L, 1L)).thenReturn(Optional.empty());

        ReturnOrderService service = createService(
                invoiceRepository, returnOrderRepository, exchangeOrderRepository, mock(InventorySecurityService.class), userRepository);

        assertThrows(ResponseStatusException.class, () -> service.createReturnOrder(createRequest(1, null)));
    }

    private ReturnOrderService createService(InvoiceRepository invoiceRepository,
                                             ReturnOrderRepository returnOrderRepository,
                                             ExchangeOrderRepository exchangeOrderRepository,
                                             InventorySecurityService inventorySecurityService,
                                             UserRepository userRepository) {
        SellOrderAdjustmentSupport support = new SellOrderAdjustmentSupport(
                invoiceRepository, mock(NonGstInvoiceRepository.class), returnOrderRepository,
                exchangeOrderRepository, inventorySecurityService, userRepository);
        return new ReturnOrderService(support, returnOrderRepository);
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

    private Invoice createInvoice() {
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
        invoice.setItems(List.of(item.toInvoiceItem()));

        Tenant tenant = new Tenant();
        tenant.setId(1);
        invoice.setTenant(tenant);
        return invoice;
    }

    private ReturnOrderRequest createRequest(int quantity, BigDecimal clientUnitPrice) {
        ReturnOrderRequest request = new ReturnOrderRequest();
        request.setInvoiceId(10L);
        request.setReason("Damaged item");
        ReturnOrderRequest.Item item = new ReturnOrderRequest.Item();
        item.setProductId(5L);
        item.setQuantity(quantity);
        item.setUnitPrice(clientUnitPrice);
        request.setItems(List.of(item));
        return request;
    }
}
