package org.ib.ibbackend.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerLedgerRow(
        String id,
        String billType,
        String invoiceNo,
        String customerName,
        String customerPhone,
        Instant date,
        BigDecimal total,
        BigDecimal paid,
        BigDecimal invoiceDue,
        String paymentMethod,
        String paymentStatus,
        BigDecimal openingBalance,
        BigDecimal closingBalance
) {
}
