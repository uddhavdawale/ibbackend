package org.ib.ibbackend.dto;

import java.math.BigDecimal;
import java.util.List;

public record CustomerLedgerResponse(
        List<CustomerLedgerRow> content,
        PageMetadata page,
        Summary summary,
        List<CustomerLedgerCustomerOption> customerOptions
) {
    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public record Summary(BigDecimal billed, BigDecimal received, BigDecimal customerDue, long customerCount) {
    }
}
