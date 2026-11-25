package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CustomerLedgerCustomerOption;
import org.ib.ibbackend.dto.CustomerLedgerResponse;
import org.ib.ibbackend.dto.CustomerLedgerRow;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CustomerLedgerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerLedgerServiceTest {
    private final CustomerLedgerRepository repository = mock(CustomerLedgerRepository.class);
    private final CustomerLedgerService service = new CustomerLedgerService(repository);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void returnsRequestedPageSizeAndCustomerOptionsOnlyForFirstPage() {
        TenantContext.setCurrentTenant(7L);
        when(repository.countMatching(7, null, null, null, null)).thenReturn(26L);
        when(repository.findPage(7, null, null, null, null, 0, 100)).thenReturn(List.of());
        when(repository.findPage(7, null, null, null, null, 0, 25)).thenReturn(List.of());
        when(repository.findPage(7, null, null, null, null, 25, 25)).thenReturn(List.of());
        when(repository.summarize(7, null, null, null, null)).thenReturn(
                new CustomerLedgerResponse.Summary(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0));
        when(repository.findCustomerOptions(7)).thenReturn(
                List.of(new CustomerLedgerCustomerOption("id:3", "Customer", "9000000000")));

        CustomerLedgerResponse firstPage = service.getLedger(0, 100, null, null, null, null);
        CustomerLedgerResponse secondPage = service.getLedger(1, 25, null, null, null, null);

        assertEquals(100, firstPage.page().size());
        assertEquals(1, firstPage.page().totalPages());
        assertEquals(26, firstPage.page().totalElements());
        assertEquals("id:3", firstPage.customerOptions().get(0).key());
        assertEquals(List.of(), secondPage.customerOptions());
        verify(repository).findPage(7, null, null, null, null, 25, 25);
        verify(repository).findCustomerOptions(7);
    }

    @Test
    void capsPageSizeAtMaximumAndUsesZeroBasedOffset() {
        TenantContext.setCurrentTenant(7L);
        when(repository.countMatching(7, null, null, null, null)).thenReturn(101L);
        when(repository.findPage(7, null, null, null, null, 100, 100)).thenReturn(List.of());
        when(repository.summarize(7, null, null, null, null)).thenReturn(
                new CustomerLedgerResponse.Summary(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0));

        CustomerLedgerResponse response = service.getLedger(1, 500, null, null, null, null);

        assertEquals(100, response.page().size());
        assertEquals(2, response.page().totalPages());
        verify(repository).findPage(7, null, null, null, null, 100, 100);
    }

    @Test
    void rejectsUnauthenticatedTenantAndReversedDates() {
        assertThrows(AccessDeniedException.class,
                () -> service.getLedger(0, 25, null, null, null, null));

        TenantContext.setCurrentTenant(7L);
        assertThrows(ResponseStatusException.class, () -> service.getLedger(
                0, 25, null, null, LocalDate.parse("2026-11-24"), LocalDate.parse("2026-11-23")));
    }

    @Test
    void streamsEscapedCsvAndNeutralizesSpreadsheetFormulas() throws Exception {
        TenantContext.setCurrentTenant(9L);
        CustomerLedgerRow row = new CustomerLedgerRow(
                "GST:4", "GST", "INV,4", "=2+2", "9000000000",
                Instant.parse("2026-11-23T12:00:00Z"),
                new BigDecimal("100.00"), new BigDecimal("25.00"), new BigDecimal("75.00"),
                "UPI", "PARTIAL", BigDecimal.ZERO, new BigDecimal("75.00"));
        doAnswer(invocation -> {
            Consumer<CustomerLedgerRow> consumer = invocation.getArgument(5);
            consumer.accept(row);
            return null;
        }).when(repository).streamMatchingRows(eq(9), isNull(), isNull(), isNull(), isNull(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeCsv(null, null, null, null, output);
        String csv = output.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(csv.contains("\"'=2+2\""));
        assertTrue(csv.contains("\"INV,4\""));
        assertTrue(csv.contains("\"100.00\",\"25.00\",\"75.00\""));
        verify(repository).streamMatchingRows(eq(9), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void exportUsesTenantSnapshotAfterTenantContextIsCleared() throws Exception {
        TenantContext.setCurrentTenant(9L);
        Integer tenantId = service.requireAuthorizedTenantId();
        TenantContext.clear();

        doAnswer(invocation -> null)
                .when(repository).streamMatchingRows(eq(9), isNull(), isNull(), isNull(), isNull(), any());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.writeCsv(tenantId, null, null, null, null, output);

        verify(repository).streamMatchingRows(eq(9), isNull(), isNull(), isNull(), isNull(), any());
    }
}
