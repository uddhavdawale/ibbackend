package org.ib.ibbackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ib.ibbackend.entity.Invoice;
import org.ib.ibbackend.entity.NonGstInvoice;
import org.ib.ibbackend.repository.InvoiceRepository;
import org.ib.ibbackend.repository.NonGstInvoiceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class InvoiceIdempotencyService {
    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,128}");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final InvoiceRepository invoiceRepository;
    private final NonGstInvoiceRepository nonGstInvoiceRepository;

    public InvoiceIdempotencyService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            InvoiceRepository invoiceRepository,
            NonGstInvoiceRepository nonGstInvoiceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.invoiceRepository = invoiceRepository;
        this.nonGstInvoiceRepository = nonGstInvoiceRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Invoice> claimGstInvoice(Long tenantId, String key, Object request) {
        String requestHash = validateAndHash(key, request);
        if (claim(tenantId, key, "GST", requestHash)) {
            return Optional.empty();
        }

        IdempotencyRecord record = findRecord(tenantId, key);
        verifyReplay(record, "GST", requestHash);
        if (record.gstInvoiceId() == null) {
            throw inProgress();
        }
        Invoice invoice = invoiceRepository.findByIdAndTenantId(record.gstInvoiceId(), tenantId)
                .orElseThrow(this::inProgress);
        return Optional.of(invoice);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<NonGstInvoice> claimNonGstInvoice(Long tenantId, String key, Object request) {
        String requestHash = validateAndHash(key, request);
        if (claim(tenantId, key, "NON_GST", requestHash)) {
            return Optional.empty();
        }

        IdempotencyRecord record = findRecord(tenantId, key);
        verifyReplay(record, "NON_GST", requestHash);
        if (record.nonGstInvoiceId() == null) {
            throw inProgress();
        }
        NonGstInvoice invoice = nonGstInvoiceRepository.findByIdAndTenantId(record.nonGstInvoiceId(), tenantId)
                .orElseThrow(this::inProgress);
        return Optional.of(invoice);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeGstInvoice(Long tenantId, String key, Long invoiceId) {
        complete(tenantId, key, "GST", "invoice_id", invoiceId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeNonGstInvoice(Long tenantId, String key, Long invoiceId) {
        complete(tenantId, key, "NON_GST", "non_gst_invoice_id", invoiceId);
    }

    private boolean claim(Long tenantId, String key, String invoiceType, String requestHash) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO invoice_idempotency (tenant_id, idempotency_key, invoice_type, request_hash)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """,
                tenantId, key, invoiceType, requestHash);
        return inserted == 1;
    }

    private IdempotencyRecord findRecord(Long tenantId, String key) {
        List<IdempotencyRecord> records = jdbcTemplate.query(
                """
                SELECT invoice_type, request_hash, invoice_id, non_gst_invoice_id
                FROM invoice_idempotency
                WHERE tenant_id = ? AND idempotency_key = ?
                """,
                (resultSet, rowNumber) -> new IdempotencyRecord(
                        resultSet.getString("invoice_type"),
                        resultSet.getString("request_hash"),
                        nullableLong(resultSet, "invoice_id"),
                        nullableLong(resultSet, "non_gst_invoice_id")),
                tenantId, key);
        if (records.isEmpty()) {
            throw inProgress();
        }
        return records.get(0);
    }

    private void verifyReplay(IdempotencyRecord record, String invoiceType, String requestHash) {
        if (!invoiceType.equals(record.invoiceType()) || !requestHash.equals(record.requestHash())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency-Key was already used for a different invoice request");
        }
    }

    private void complete(Long tenantId, String key, String invoiceType, String invoiceColumn, Long invoiceId) {
        int updated = jdbcTemplate.update(
                "UPDATE invoice_idempotency SET " + invoiceColumn
                        + " = ? WHERE tenant_id = ? AND idempotency_key = ?"
                        + " AND invoice_type = ? AND invoice_id IS NULL AND non_gst_invoice_id IS NULL",
                invoiceId, tenantId, key, invoiceType);
        if (updated != 1) {
            throw new IllegalStateException("Could not complete invoice idempotency record");
        }
    }

    private String validateAndHash(String key, Object request) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain 1 to 128 visible ASCII characters");
        }
        try {
            byte[] requestBytes = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(requestBytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint invoice request", exception);
        }
    }

    private Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private ResponseStatusException inProgress() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Invoice request with this Idempotency-Key is not available; check invoice history before retrying");
    }

    private record IdempotencyRecord(
            String invoiceType,
            String requestHash,
            Long gstInvoiceId,
            Long nonGstInvoiceId) {
    }
}
