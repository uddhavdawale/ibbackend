package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.PaymentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {
    Optional<PaymentRecord> findByTenant_IdAndIdempotencyKey(Integer tenantId, String idempotencyKey);

    @Query("SELECT p FROM PaymentRecord p WHERE p.id = :paymentId AND p.tenant.id = :tenantId")
    Optional<PaymentRecord> findByIdAndTenantId(@Param("paymentId") Long paymentId,
                                                @Param("tenantId") Integer tenantId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentRecord p WHERE p.id = :paymentId AND p.tenant.id = :tenantId")
    Optional<PaymentRecord> findByIdAndTenantIdForUpdate(@Param("paymentId") Long paymentId,
                                                         @Param("tenantId") Integer tenantId);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentRecord p " +
            "WHERE p.reversalOf.id = :paymentId AND p.tenant.id = :tenantId AND p.source = 'REFUND'")
    BigDecimal sumRefundsForPayment(@Param("paymentId") Long paymentId,
                                    @Param("tenantId") Integer tenantId);

    @Query(value = """
            SELECT p.id AS paymentId,
                   COALESCE(p.invoice_id, p.non_gst_invoice_id) AS invoiceId,
                   COALESCE(i.invoice_no, ngi.invoice_no) AS invoiceNo,
                   COALESCE(i.customer_name, ngi.customer_name) AS customerName,
                   CASE WHEN p.source = 'REFUND' THEN -p.amount ELSE p.amount END AS amount,
                   CASE WHEN p.source = 'REFUND' THEN 0 ELSE GREATEST(p.amount - COALESCE((
                       SELECT SUM(r.amount) FROM payment_records r
                       WHERE r.reversal_of_payment_id = p.id AND r.tenant_id = p.tenant_id
                         AND r.source = 'REFUND'
                   ), 0), 0) END AS refundableAmount,
                   p.method AS method,
                   p.status AS status,
                   p.receipt_no AS receiptNo,
                   p.reference_no AS referenceNo,
                   COALESCE(u.owner_name, u.username, u.email) AS collectorName,
                   p.collector_user_id AS collectorId,
                   p.source AS source,
                   p.collected_at AS collectedAt
            FROM payment_records p
            LEFT JOIN invoices i ON i.id = p.invoice_id
            LEFT JOIN non_gst_invoices ngi ON ngi.id = p.non_gst_invoice_id
            LEFT JOIN users u ON u.id = p.collector_user_id
            WHERE p.tenant_id = :tenantId
              AND p.status IN ('RECEIVED', 'REFUNDED')
              AND p.collected_at >= :fromTime
              AND p.collected_at < :toTime
              AND (:collectorId IS NULL OR p.collector_user_id = :collectorId)
              AND (:method IS NULL OR p.method = :method)
              AND (:invoiceId IS NULL OR p.invoice_id = :invoiceId OR p.non_gst_invoice_id = :invoiceId)
            ORDER BY p.collected_at DESC, p.id DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM payment_records p
            WHERE p.tenant_id = :tenantId
              AND p.status IN ('RECEIVED', 'REFUNDED')
              AND p.collected_at >= :fromTime
              AND p.collected_at < :toTime
              AND (:collectorId IS NULL OR p.collector_user_id = :collectorId)
              AND (:method IS NULL OR p.method = :method)
              AND (:invoiceId IS NULL OR p.invoice_id = :invoiceId OR p.non_gst_invoice_id = :invoiceId)
            """,
            nativeQuery = true)
    Page<PaymentReportRow> findReportRows(
            @Param("tenantId") Integer tenantId,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime,
            @Param("collectorId") Long collectorId,
            @Param("method") String method,
            @Param("invoiceId") Long invoiceId,
            Pageable pageable);

    @Query(value = """
            SELECT p.id AS paymentId,
                   CASE WHEN p.invoice_id IS NOT NULL THEN p.invoice_id ELSE p.non_gst_invoice_id END AS invoiceId,
                   CASE WHEN p.invoice_id IS NOT NULL THEN i.invoice_no ELSE ngi.invoice_no END AS invoiceNo,
                   CASE WHEN p.invoice_id IS NOT NULL THEN i.customer_name ELSE ngi.customer_name END AS customerName,
                   p.amount AS amount,
                   GREATEST(p.amount - COALESCE((
                       SELECT SUM(r.amount) FROM payment_records r
                       WHERE r.reversal_of_payment_id = p.id AND r.tenant_id = p.tenant_id
                         AND r.source = 'REFUND'
                   ), 0), 0) AS refundableAmount,
                   p.method AS method,
                   p.status AS status,
                   p.receipt_no AS receiptNo,
                   p.reference_no AS referenceNo,
                   COALESCE(u.owner_name, u.username, u.email) AS collectorName,
                   p.collector_user_id AS collectorId,
                   p.source AS source,
                   p.collected_at AS collectedAt
            FROM payment_records p
            LEFT JOIN invoices i ON i.id = p.invoice_id AND i.tenant_id = p.tenant_id
            LEFT JOIN non_gst_invoices ngi ON ngi.id = p.non_gst_invoice_id AND ngi.tenant_id = p.tenant_id
            LEFT JOIN users u ON u.id = p.collector_user_id AND u.tenant_id = p.tenant_id
            WHERE p.tenant_id = :tenantId
              AND p.status = 'RECEIVED'
              AND p.source <> 'REFUND'
              AND ((:billType = 'gst' AND p.invoice_id = :invoiceId)
                   OR (:billType = 'non-gst' AND p.non_gst_invoice_id = :invoiceId))
              AND GREATEST(p.amount - COALESCE((
                  SELECT SUM(r.amount) FROM payment_records r
                  WHERE r.reversal_of_payment_id = p.id AND r.tenant_id = p.tenant_id
                    AND r.source = 'REFUND'
              ), 0), 0) > 0
            ORDER BY p.collected_at DESC, p.id DESC
            """, nativeQuery = true)
    List<PaymentReportRow> findRefundableRowsForInvoice(
            @Param("tenantId") Integer tenantId,
            @Param("invoiceId") Long invoiceId,
            @Param("billType") String billType);

    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN source = 'REFUND' THEN -amount ELSE amount END), 0),
                   COUNT(*),
                   COALESCE(SUM(CASE WHEN method = 'CASH' THEN CASE WHEN source = 'REFUND' THEN -amount ELSE amount END ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN method = 'UPI' THEN CASE WHEN source = 'REFUND' THEN -amount ELSE amount END ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN method = 'CARD' THEN CASE WHEN source = 'REFUND' THEN -amount ELSE amount END ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN source = 'REFUND' THEN amount ELSE 0 END), 0)
            FROM payment_records
            WHERE tenant_id = :tenantId
              AND status IN ('RECEIVED', 'REFUNDED')
              AND collected_at >= :fromTime
              AND collected_at < :toTime
              AND (:collectorId IS NULL OR collector_user_id = :collectorId)
              AND (:method IS NULL OR method = :method)
              AND (:invoiceId IS NULL OR invoice_id = :invoiceId OR non_gst_invoice_id = :invoiceId)
            """, nativeQuery = true)
    Object[] summarize(
            @Param("tenantId") Integer tenantId,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime,
            @Param("collectorId") Long collectorId,
            @Param("method") String method,
            @Param("invoiceId") Long invoiceId);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0)
            FROM PaymentRecord p
            WHERE p.tenant.id = :tenantId
              AND p.collector.id = :collectorId
              AND p.method = 'CASH'
              AND p.status = 'RECEIVED'
              AND p.collectedAt >= :fromTime
              AND p.collectedAt < :toTime
            """)
    BigDecimal sumCashByCollectorAndPeriod(
            @Param("tenantId") Integer tenantId,
            @Param("collectorId") Long collectorId,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0)
            FROM PaymentRecord p
            WHERE p.tenant.id = :tenantId
              AND p.collector.id = :collectorId
              AND p.method = 'CASH'
              AND p.status = 'REFUNDED'
              AND p.source = 'REFUND'
              AND p.collectedAt >= :fromTime
              AND p.collectedAt < :toTime
            """)
    BigDecimal sumCashRefundsByCollectorAndPeriod(
            @Param("tenantId") Integer tenantId,
            @Param("collectorId") Long collectorId,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime);
}
