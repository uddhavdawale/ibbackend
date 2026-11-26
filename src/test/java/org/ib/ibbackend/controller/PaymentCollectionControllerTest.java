package org.ib.ibbackend.controller;

import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.service.PaymentCollectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentCollectionControllerTest {
    private final PaymentCollectionService paymentCollectionService = mock(PaymentCollectionService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new PaymentCollectionController(paymentCollectionService))
            .build();

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void paymentsReturnsThePaymentReportContractWithDefaultDates() throws Exception {
        TenantContext.setCurrentTenant(7L);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Map<String, Object> response = Map.of(
                "from", today.toString(),
                "to", today.toString(),
                "summary", Map.of(
                        "netSales", 0.00,
                        "totalCollected", 1250.00,
                        "totalTransactions", 3,
                        "cashCollected", 500.00,
                        "upiCollected", 750.00,
                        "cardCollected", 0.00,
                        "totalRefunds", 0.00,
                        "outstandingAmount", 3200.00),
                "payments", List.of(),
                "page", 0,
                "size", 20,
                "totalPages", 0,
                "totalElements", 0);
        when(paymentCollectionService.getReport(
                today, today, null, null, null, 0, 20)).thenReturn(response);

        mockMvc.perform(get("/api/payments")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(today.toString()))
                .andExpect(jsonPath("$.to").value(today.toString()))
                .andExpect(jsonPath("$.summary.totalCollected").value(1250.00))
                .andExpect(jsonPath("$.summary.totalTransactions").value(3))
                .andExpect(jsonPath("$.summary.outstandingAmount").value(3200.00))
                .andExpect(jsonPath("$.payments").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(paymentCollectionService).getReport(
                today, today, null, null, null, 0, 20);
    }

    @Test
    void refundablePaymentsEndpointRequiresTenantAndDelegatesInvoiceType() throws Exception {
        TenantContext.setCurrentTenant(7L);
        when(paymentCollectionService.getRefundablePaymentsForCancelledInvoice(41L, "non-gst"))
                .thenReturn(List.of(Map.of("paymentId", 15L, "refundableAmount", 25.00)));

        mockMvc.perform(get("/api/payments/refundable")
                        .param("invoiceId", "41")
                        .param("billType", "non-gst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(15))
                .andExpect(jsonPath("$[0].refundableAmount").value(25.00));

        verify(paymentCollectionService).getRefundablePaymentsForCancelledInvoice(41L, "non-gst");
    }
}
