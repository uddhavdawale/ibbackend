package org.ib.ibbackend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CustomerDirectoryPageResponse(
        List<CustomerRow> content,
        PageMetadata page
) {
    public record CustomerRow(
            String id,
            String customerName,
            String shopName,
            String address,
            String gstNumber,
            String phone,
            String email,
            BigDecimal dueBalance,
            int previousOrderCount,
            Long lastOrderId,
            String lastOrderNo,
            LocalDate lastInvoiceDate
    ) {
    }

    public record PageMetadata(
            int number,
            int size,
            long totalElements,
            int totalPages,
            long dueCustomerCount
    ) {
    }
}
