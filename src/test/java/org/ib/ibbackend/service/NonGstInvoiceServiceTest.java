package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.NonGstInvoiceRequest;
import org.ib.ibbackend.dto.NonGstInvoiceRequestItem;
import org.ib.ibbackend.entity.NonGstInvoice;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.ib.ibbackend.repository.ProductRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        SequenceService sequenceService = mock(SequenceService.class);

        NonGstInvoiceService service = new NonGstInvoiceService(invoiceRepository, productRepository, userRepository, sequenceService);

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
        when(sequenceService.nextValue("non_gst_invoice")).thenReturn(104);
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
        request.setPaymentStatus("paid");
        request.setStatus("paid");
        request.setBillType("non-gst");
        request.setIsGstInvoice(false);
        request.setItems(List.of(item));

        NonGstInvoice saved = service.createNonGstInvoice(request);

        assertEquals("non-gst", saved.getBillType());
        assertFalse(saved.isGstInvoice());
        assertEquals(new BigDecimal("300.00"), saved.getSubtotal());
        assertEquals(new BigDecimal("0.00"), saved.getGstAmount());
        assertEquals(new BigDecimal("300.00"), saved.getGrandTotal());
        assertEquals("INV-2026/0104", saved.getInvoiceNo());
        assertEquals(8, product.getQuantity());
        assertEquals(7, saved.getTenant().getId());
        assertEquals(22L, saved.getUser().getId());
    }

    @Test
    void shouldRejectNonZeroTaxValuesForNonGstInvoice() {
        NonGstInvoiceRepository invoiceRepository = mock(NonGstInvoiceRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        SequenceService sequenceService = mock(SequenceService.class);

        NonGstInvoiceService service = new NonGstInvoiceService(invoiceRepository, productRepository, userRepository, sequenceService);

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
}
