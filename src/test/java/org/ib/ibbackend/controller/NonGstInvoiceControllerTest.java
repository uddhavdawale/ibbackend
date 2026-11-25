package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.NonGstInvoiceRequest;
import org.ib.ibbackend.dto.NonGstInvoiceRequestItem;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.NonGstInvoice;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.service.NonGstInvoiceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NonGstInvoiceControllerTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private org.ib.ibbackend.repository.InvoiceItemRepository invoiceItemRepository;

    @Mock
    private org.ib.ibbackend.repository.ProductRepository productRepository;

    @Mock
    private org.ib.ibbackend.repository.UserRepository userRepository;

    @InjectMocks
    private InvoiceController invoiceController;

    @Test
    void shouldReturnSuccessPayloadForCreateNonGstInvoice() {
        NonGstInvoiceService service = mock(NonGstInvoiceService.class);
        NonGstInvoiceController controller = new NonGstInvoiceController(service);

        NonGstInvoice invoice = new NonGstInvoice();
        invoice.setId(104L);
        invoice.setInvoiceNo("INV-104");

        NonGstInvoiceRequest request = new NonGstInvoiceRequest();
        request.setCustomerName("Walk-in Customer");
        request.setBillType("non-gst");
        request.setIsGstInvoice(false);

        NonGstInvoiceRequestItem item = new NonGstInvoiceRequestItem();
        item.setProductId(12L);
        item.setPrice(new BigDecimal("150"));
        item.setQty(2);
        item.setGstRate(0);
        item.setTaxAmount(BigDecimal.ZERO);
        request.setItems(List.of(item));

        when(service.createNonGstInvoice(request)).thenReturn(invoice);

        Map<String, Object> body = controller.createNonGstInvoice(request).getBody();
        assertTrue((Boolean) body.get("success"));
        assertEquals("INV-104", body.get("invoiceNo"));
        assertEquals(104L, body.get("id"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldKeepGstInvoiceListVisibleWhenBillTypeIsAll() {
        Tenant tenant = new Tenant();
        tenant.setId(7);

        Invoice invoice = new Invoice();
        invoice.setId(11L);
        invoice.setInvoiceNo("INV-11");
        invoice.setCustomerName("Rohan");
        invoice.setCustomerPhone("9876543210");
        invoice.setCustomerEmail("rohan@example.com");
        invoice.setCustomerAddress("17 Market Road");
        invoice.setPlaceOfSupplyState("MH");
        invoice.setDate(LocalDate.now());
        invoice.setSubtotal(new BigDecimal("1000.00"));
        invoice.setGstAmount(new BigDecimal("180.00"));
        invoice.setGrandTotal(new BigDecimal("1180.00"));
        invoice.setPaidAmount(new BigDecimal("0.00"));
        invoice.setStatus(Invoice.InvoiceStatus.PAID);
        invoice.setPaymentStatus(Invoice.PaymentStatus.PAID);
        invoice.setTenant(tenant);

        TenantContext.setCurrentTenant(7L);
        when(invoiceRepository.findByTenantId(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(invoice), PageRequest.of(0, 10), 1));

        ResponseEntity<Page<Map<String, Object>>> response = invoiceController.getInvoices(0, 10, null, "all", "");

        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getTotalElements());
        assertEquals("INV-11", response.getBody().getContent().get(0).get("invoiceNo"));
        assertEquals("17 Market Road", response.getBody().getContent().get(0).get("customerAddress"));
        assertEquals("MH", response.getBody().getContent().get(0).get("placeOfSupplyState"));
    }
}
