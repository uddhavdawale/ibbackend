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
import org.ib.ibbackend.service.InvoiceIdempotencyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NonGstInvoiceServiceTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void shouldCreateNonGstInvoiceWithZeroTaxAndTenantRelations() {
        NonGstInvoiceRepository invoiceRepository = mock(NonGstInvoiceRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        NonGstInvoiceService service = new NonGstInvoiceService(invoiceRepository, productRepository, userRepository);

        Tenant tenant = new Tenant();
        tenant.setId(7);

        User user = new User();
        user.setId(22L);
        user.setEmail("owner@example.com");
        user.setTenant(tenant);

        Product product = new Product();
        product.setId(12L);
        product.setName("Notebook");
        product.setSku("NB-100");
        product.setQuantity(10);
        product.setHsnSac("4820");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner@example.com", null)
        );
        TenantContext.setCurrentTenant(7L);

        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(productRepository.findByIdAndTenantIdForUpdate(12L, 7L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepository.save(any(NonGstInvoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NonGstInvoiceRequestItem item = new NonGstInvoiceRequestItem();
        item.setProductId(12L);
        item.setName("Notebook");
        item.setSku("NB-100");
        item.setHsn("4820");
        item.setGstRate(0);
        item.setCgst(BigDecimal.ZERO);
        item.setSgst(BigDecimal.ZERO);
        item.setIgst(BigDecimal.ZERO);
        item.setPrice(new BigDecimal("150"));
        item.setQty(2);
        item.setCategory("stationery");
        item.setTaxAmount(BigDecimal.ZERO);

        NonGstInvoiceRequest request = new NonGstInvoiceRequest();
        request.setCustomerName("Walk-in Customer");
        request.setCustomerPhone("0000000000");
        request.setPaymentMethod("UPI");
        request.setPaymentStatus("partial");
        request.setStatus("partial");
        request.setReceivedAmount(new BigDecimal("125.00"));
        request.setBillType("non-gst");
        request.setIsGstInvoice(false);
        request.setItems(List.of(item));

        NonGstInvoice saved = service.createNonGstInvoice(request);

        assertEquals("non-gst", saved.getBillType());
        assertFalse(saved.isGstInvoice());
        assertEquals(new BigDecimal("300.00"), saved.getSubtotal());
        assertEquals(new BigDecimal("0.00"), saved.getGstAmount());
        assertEquals(new BigDecimal("300.00"), saved.getGrandTotal());
        assertEquals(new BigDecimal("125.00"), saved.getPaidAmount());
        assertEquals("PARTIAL", saved.getPaymentStatus());
        assertEquals("PENDING", saved.getStatus());
        assertTrue(saved.getInvoiceNo().matches("INV-\\d{12}-\\d{4}"));
        assertEquals(8, product.getQuantity());
        assertEquals(7, saved.getTenant().getId());
        assertEquals(22L, saved.getUser().getId());
    }

    @Test
    void shouldRejectNonZeroTaxValuesForNonGstInvoice() {
        NonGstInvoiceRepository invoiceRepository = mock(NonGstInvoiceRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        NonGstInvoiceService service = new NonGstInvoiceService(invoiceRepository, productRepository, userRepository);

        Tenant tenant = new Tenant();
        tenant.setId(7);

        User user = new User();
        user.setEmail("owner@example.com");
        user.setTenant(tenant);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner@example.com", null)
        );
        TenantContext.setCurrentTenant(7L);

        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        NonGstInvoiceRequestItem item = new NonGstInvoiceRequestItem();
        item.setProductId(12L);
        item.setPrice(new BigDecimal("150"));
        item.setQty(2);
        item.setGstRate(1);
        item.setTaxAmount(BigDecimal.ONE);

        NonGstInvoiceRequest request = new NonGstInvoiceRequest();
        request.setCustomerName("Walk-in Customer");
        request.setBillType("non-gst");
        request.setIsGstInvoice(false);
        request.setItems(List.of(item));

        assertThrows(ResponseStatusException.class, () -> service.createNonGstInvoice(request));
    }

    @Test
    void replaysNonGstInvoiceForSameIdempotencyKeyWithoutRepeatingProductLookup() {
        NonGstInvoiceRepository invoiceRepository = mock(NonGstInvoiceRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        InvoiceIdempotencyService idempotencyService = mock(InvoiceIdempotencyService.class);
        NonGstInvoiceService service = new NonGstInvoiceService(
                invoiceRepository, productRepository, userRepository,
                null, null, null, null, idempotencyService);

        Tenant tenant = new Tenant();
        tenant.setId(7);
        User user = new User();
        user.setId(22L);
        user.setTenant(tenant);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner@example.com", null));
        TenantContext.setCurrentTenant(7L);
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));

        NonGstInvoice existing = new NonGstInvoice();
        existing.setInvoiceNo("INV-EXISTING");
        when(idempotencyService.claimNonGstInvoice(eq(7L), eq("retry-key"), any()))
                .thenReturn(Optional.of(existing));

        NonGstInvoiceRequestItem item = new NonGstInvoiceRequestItem();
        item.setProductId(12L);
        item.setPrice(new BigDecimal("10.00"));
        item.setQty(1);
        item.setGstRate(0);
        item.setTaxAmount(BigDecimal.ZERO);
        item.setCgst(BigDecimal.ZERO);
        item.setSgst(BigDecimal.ZERO);
        item.setIgst(BigDecimal.ZERO);
        NonGstInvoiceRequest request = new NonGstInvoiceRequest();
        request.setCustomerName("Walk-in");
        request.setBillType("non-gst");
        request.setIsGstInvoice(false);
        request.setPaymentStatus("PENDING");
        request.setStatus("PENDING");
        request.setItems(List.of(item));

        NonGstInvoiceService.CreationResult result =
                service.createNonGstInvoice(request, "retry-key");

        assertTrue(result.replayed());
        assertEquals(existing, result.invoice());
        verify(productRepository, never()).findByIdAndTenantIdForUpdate(any(), any());
        verify(invoiceRepository, never()).save(any(NonGstInvoice.class));
    }

    @Test
    void shouldReturnTenantScopedInvoiceDetailsAndCurrentPaymentBalance() {
        NonGstInvoiceRepository invoiceRepository = mock(NonGstInvoiceRepository.class);
        NonGstInvoiceService service = new NonGstInvoiceService(
                invoiceRepository, mock(ProductRepository.class), mock(UserRepository.class));
        Tenant tenant = new Tenant();
        tenant.setId(7);
        User user = new User();
        user.setId(22L);

        Product product = new Product();
        product.setId(12L);
        NonGstInvoiceItem item = new NonGstInvoiceItem();
        item.setProduct(product);
        item.setName("Notebook");
        item.setPrice(new BigDecimal("2.75"));
        item.setQty(1);
        item.setItemSubtotal(new BigDecimal("2.75"));
        item.setItemTotal(new BigDecimal("2.75"));

        NonGstInvoice invoice = new NonGstInvoice();
        invoice.setId(104L);
        invoice.setInvoiceNo("INV-104");
        invoice.setDate(LocalDate.of(2026, 11, 21));
        invoice.setCustomerName("Walk-in Customer");
        invoice.setCustomerAddress("17 Market Road");
        invoice.setPlaceOfSupplyState("MH");
        invoice.setTenant(tenant);
        invoice.setUser(user);
        invoice.setGrandTotal(new BigDecimal("115.50"));
        invoice.setPaidAmount(new BigDecimal("115.50"));
        invoice.setPaymentStatus("PAID");
        invoice.setStatus("PAID");
        invoice.setItems(List.of(item));
        TenantContext.setCurrentTenant(7L);
        when(invoiceRepository.findByIdAndTenantId(104L, 7L)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.findByTenantId(eq(7L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(invoice), PageRequest.of(0, 10), 1));

        Map<String, Object> response = service.getNonGstInvoice(104L);

        assertEquals(new BigDecimal("115.50"), response.get("grandTotal"));
        assertEquals(new BigDecimal("115.50"), response.get("paidAmount"));
        assertEquals(new BigDecimal("0.00"), response.get("outstandingAmount"));
        assertEquals(new BigDecimal("0.00"), response.get("dueCollection"));
        assertEquals("PAID", response.get("paymentStatus"));
        assertEquals("Walk-in Customer", response.get("customerName"));
        assertEquals("17 Market Road", response.get("customerAddress"));
        assertEquals("MH", response.get("placeOfSupplyState"));
        Page<Map<String, Object>> invoicePage = service.listNonGstInvoices(0, 10, "", "all");
        assertEquals("17 Market Road", invoicePage.getContent().get(0).get("customerAddress"));
        assertEquals("MH", invoicePage.getContent().get(0).get("placeOfSupplyState"));
        List<?> items = (List<?>) response.get("items");
        assertEquals(1, items.size());
        assertEquals("Notebook", ((Map<?, ?>) items.get(0)).get("name"));
    }
}
