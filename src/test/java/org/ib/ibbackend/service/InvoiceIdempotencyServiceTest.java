package org.ib.ibbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ib.ibbackend.dto.InvoiceRequest;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvoiceIdempotencyServiceTest {
    @Test
    void replaysOriginalInvoiceAndRejectsSameKeyWithChangedRequest() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
        NonGstInvoiceRepository nonGstInvoiceRepository = mock(NonGstInvoiceRepository.class);
        InvoiceIdempotencyService service = new InvoiceIdempotencyService(
                jdbcTemplate, objectMapper, invoiceRepository, nonGstInvoiceRepository);

        InvoiceRequest originalRequest = new InvoiceRequest();
        originalRequest.setCustomerName("Walk-in");
        String requestHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(originalRequest)));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> rowMapper = invocation.getArgument(1);
                    java.sql.ResultSet resultSet = mock(java.sql.ResultSet.class);
                    when(resultSet.getString("invoice_type")).thenReturn("GST");
                    when(resultSet.getString("request_hash")).thenReturn(requestHash);
                    when(resultSet.getLong("invoice_id")).thenReturn(77L);
                    when(resultSet.getLong("non_gst_invoice_id")).thenReturn(0L);
                    when(resultSet.wasNull()).thenReturn(false, true);
                    return List.of(rowMapper.mapRow(resultSet, 0));
                });

        Invoice existing = new Invoice();
        existing.setInvoiceNo("INV-EXISTING");
        when(invoiceRepository.findByIdAndTenantId(77L, 7L)).thenReturn(Optional.of(existing));

        Optional<Invoice> replay = service.claimGstInvoice(7L, "retry-key", originalRequest);

        assertThat(replay).containsSame(existing);

        InvoiceRequest changedRequest = new InvoiceRequest();
        changedRequest.setCustomerName("Different customer");
        assertThatThrownBy(() -> service.claimGstInvoice(7L, "retry-key", changedRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already used for a different invoice request");
    }
}
