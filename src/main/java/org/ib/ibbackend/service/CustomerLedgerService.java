package org.ib.ibbackend.service;

import org.ib.ibbackend.dto.CustomerLedgerCustomerOption;
import org.ib.ibbackend.dto.CustomerLedgerResponse;
import org.ib.ibbackend.dto.CustomerLedgerRow;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.CustomerLedgerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
public class CustomerLedgerService {
    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 200;
    private static final int MAX_CUSTOMER_KEY_LENGTH = 128;
    private static final String CSV_HEADER = "id,billType,invoiceNo,customerName,customerPhone,date,total,paid,"
            + "invoiceDue,paymentMethod,paymentStatus,openingBalance,closingBalance";

    private final CustomerLedgerRepository repository;

    public CustomerLedgerService(CustomerLedgerRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public CustomerLedgerResponse getLedger(
            int page, int requestedSize, String search, String customerKey, LocalDate from, LocalDate to) {
        Integer tenantId = requireTenantId();
        int size = normalizePageSize(requestedSize);
        validateRequest(page, size, search, customerKey, from, to);
        long offset = (long) page * size;
        String normalizedSearch = normalize(search);
        String normalizedCustomerKey = normalize(customerKey);
        long totalElements = repository.countMatching(tenantId, normalizedSearch, normalizedCustomerKey, from, to);
        List<CustomerLedgerRow> rows = repository.findPage(
                tenantId, normalizedSearch, normalizedCustomerKey, from, to,
                offset, size);
        CustomerLedgerResponse.Summary summary = repository.summarize(
                tenantId, normalizedSearch, normalizedCustomerKey, from, to);
        List<CustomerLedgerCustomerOption> options = page == 0
                ? repository.findCustomerOptions(tenantId)
                : List.of();
        int totalPages = totalElements == 0
                ? 0
                : (int) Math.min(Integer.MAX_VALUE, ((totalElements - 1) / size) + 1);
        return new CustomerLedgerResponse(
                rows,
                new CustomerLedgerResponse.PageMetadata(page, size, totalElements, totalPages),
                summary,
                options);
    }

    @Transactional(readOnly = true)
    public void writeCsv(String search, String customerKey, LocalDate from, LocalDate to, OutputStream output)
            throws IOException {
        Integer tenantId = requireTenantId();
        writeCsv(tenantId, search, customerKey, from, to, output);
    }

    public Integer requireAuthorizedTenantId() {
        return requireTenantId();
    }

    public void writeCsv(
            Integer tenantId, String search, String customerKey, LocalDate from, LocalDate to, OutputStream output)
            throws IOException {
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        validateRequest(0, DEFAULT_PAGE_SIZE, search, customerKey, from, to);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write(CSV_HEADER);
        writer.newLine();
        repository.streamMatchingRows(tenantId, normalize(search), normalize(customerKey), from, to, row -> {
            try {
                writeCsvRow(writer, row);
            } catch (IOException exception) {
                throw new CsvWriteException(exception);
            }
        });
        writer.flush();
    }

    private Integer requireTenantId() {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new AccessDeniedException("Access denied");
        }
        try {
            return Math.toIntExact(tenantId);
        } catch (ArithmeticException exception) {
            throw new AccessDeniedException("Access denied");
        }
    }

    private void validateRequest(
            int page, int size, String search, String customerKey, LocalDate from, LocalDate to) {
        if (page < 0 || size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be non-negative and size positive");
        }
        if ((long) page * size > Long.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page is too large");
        }
        if (search != null && search.length() > MAX_SEARCH_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search is too long");
        }
        if (customerKey != null && customerKey.length() > MAX_CUSTOMER_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer key is too long");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "From date must not be after to date");
        }
    }

    private int normalizePageSize(int requestedSize) {
        if (requestedSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requestedSize, MAX_PAGE_SIZE);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void writeCsvRow(BufferedWriter writer, CustomerLedgerRow row) throws IOException {
        writeCsvCells(writer, List.of(
                safeText(row.id()),
                safeText(row.billType()),
                safeText(row.invoiceNo()),
                safeText(row.customerName()),
                safeText(row.customerPhone()),
                safeText(row.date() == null ? "" : row.date().toString()),
                decimal(row.total()),
                decimal(row.paid()),
                decimal(row.invoiceDue()),
                safeText(row.paymentMethod()),
                safeText(row.paymentStatus()),
                decimal(row.openingBalance()),
                decimal(row.closingBalance())));
    }

    private void writeCsvCells(BufferedWriter writer, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            String cell = cells.get(i);
            writer.write('"');
            writer.write(cell.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.newLine();
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.stripLeading();
        if (!normalized.isEmpty() && "=+-@".indexOf(normalized.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private String decimal(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    public static class CsvWriteException extends RuntimeException {
        public CsvWriteException(IOException cause) {
            super(cause);
        }
    }
}
