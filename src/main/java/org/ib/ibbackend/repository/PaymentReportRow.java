package org.ib.ibbackend.repository;

import java.math.BigDecimal;
import java.time.Instant;

public interface PaymentReportRow {
    Long getPaymentId();
    Long getInvoiceId();
    String getInvoiceNo();
    String getCustomerName();
    BigDecimal getAmount();
    BigDecimal getRefundableAmount();
    String getMethod();
    String getStatus();
    String getReceiptNo();
    String getReferenceNo();
    String getCollectorName();
    Long getCollectorId();
    String getSource();
    Instant getCollectedAt();
}
