package org.ib.ibbackend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CustomerInvoiceRecord(
        Long id,
        String invoiceNo,
        String customerName,
        String customerPhone,
        String customerEmail,
        BigDecimal grandTotal,
        BigDecimal paidAmount,
        LocalDate date
) {
    public CustomerInvoiceRecord(
            Long id,
            String invoiceNo,
            String customerName,
            String customerPhone,
            BigDecimal grandTotal,
            BigDecimal paidAmount,
            LocalDate date
    ) {
        this(id, invoiceNo, customerName, customerPhone, null, grandTotal, paidAmount, date);
    }
}
