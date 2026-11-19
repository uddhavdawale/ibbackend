package org.ib.ibbackend.controller;

import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.dto.CashCollectionTicketRequest;
import org.ib.ibbackend.service.CashBalanceService;
import org.ib.ibbackend.service.CashHandoverService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CashCollectionTicketControllerTest {
    private final CashHandoverService cashHandoverService = mock(CashHandoverService.class);
    private final CashBalanceService cashBalanceService = mock(CashBalanceService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new CashCollectionTicketController(cashHandoverService, cashBalanceService))
            .build();

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void listsCashCollectionTicketsInsteadOfPaymentReportRows() throws Exception {
        TenantContext.setCurrentTenant(7L);
        Map<String, Object> response = Map.of(
                "content", List.of(Map.of(
                        "id", 31,
                        "senderUserId", 11,
                        "recipientUserId", 12,
                        "declaredAmount", 40.00,
                        "status", "PENDING")),
                "page", 0,
                "size", 20,
                "totalPages", 1,
                "totalElements", 1);
        when(cashHandoverService.listCollectionTickets("PENDING", 0, 20)).thenReturn(response);

        mockMvc.perform(get("/api/cash-collection-tickets")
                        .param("status", "PENDING")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(31))
                .andExpect(jsonPath("$.content[0].declaredAmount").value(40.00))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(cashHandoverService).listCollectionTickets("PENDING", 0, 20);
    }

    @Test
    void acceptsNamedRecipientInTicketSubmissionJson() throws Exception {
        TenantContext.setCurrentTenant(7L);
        when(cashHandoverService.createCollectionTicket(any(CashCollectionTicketRequest.class)))
                .thenReturn(Map.of("id", 31, "recipientUserId", 12, "status", "PENDING"));

        mockMvc.perform(post("/api/cash-collection-tickets")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "recipientUserId": "vaishali",
                                  "declaredAmount": 40.00,
                                  "idempotencyKey": "cash-drop-1",
                                  "notes": "Evening shift"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.recipientUserId").value(12))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(cashHandoverService).createCollectionTicket(any(CashCollectionTicketRequest.class));
    }

    @Test
    void acceptsMultipartElectronicTicketWithPaymentProof() throws Exception {
        TenantContext.setCurrentTenant(7L);
        when(cashHandoverService.createCollectionTicket(
                        any(CashCollectionTicketRequest.class), any()))
                .thenReturn(Map.of(
                        "id", 32, "recipientUserId", 12, "status", "PENDING",
                        "paymentMethod", "UPI", "paymentProofAvailable", true));
        MockMultipartFile proof = new MockMultipartFile(
                "paymentProof", "receipt.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        mockMvc.perform(multipart("/api/cash-collection-tickets")
                        .file(proof)
                        .param("recipientUserId", "12")
                        .param("declaredAmount", "40.00")
                        .param("idempotencyKey", "upi-ticket-1")
                        .param("paymentMethod", "UPI"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentMethod").value("UPI"))
                .andExpect(jsonPath("$.paymentProofAvailable").value(true));

        verify(cashHandoverService).createCollectionTicket(
                any(CashCollectionTicketRequest.class), any());
    }

    @Test
    void servesPaymentProofAsPrivateInlineImage() throws Exception {
        TenantContext.setCurrentTenant(7L);
        when(cashHandoverService.getCollectionTicketPaymentProof(32L))
                .thenReturn(new CashHandoverService.PaymentProof(
                        "image/png", new byte[] {(byte) 0x89, 0x50}));

        mockMvc.perform(get("/api/cash-collection-tickets/32/payment-proof"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentType("image/png"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", "private, no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void exposesTenantCashRecipients() throws Exception {
        TenantContext.setCurrentTenant(7L);
        when(cashBalanceService.listRecipients())
                .thenReturn(Map.of("content", List.of(Map.of("id", 12, "name", "Store Admin"))));

        mockMvc.perform(get("/api/cash-collection-tickets/recipients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(12))
                .andExpect(jsonPath("$.content[0].name").value("Store Admin"));

        verify(cashBalanceService).listRecipients();
    }

    @Test
    void exposesCashBalanceEndpointWithPageDefaults() throws Exception {
        TenantContext.setCurrentTenant(7L);
        when(cashBalanceService.getBalances(0, 100))
                .thenReturn(Map.of("balance", Map.of("userId", 11, "dueToSubmit", 0.00)));

        mockMvc.perform(get("/api/cash-collection-tickets/balances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.userId").value(11))
                .andExpect(jsonPath("$.balance.dueToSubmit").value(0.00));

        verify(cashBalanceService).getBalances(0, 100);
    }
}
