package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.NonGstInvoiceRequest;
import org.ib.ibbackend.dto.NonGstInvoiceRequestItem;
import org.ib.ibbackend.entity.NonGstInvoice;
import org.ib.ibbackend.service.NonGstInvoiceService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NonGstInvoiceControllerTest {

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
}
