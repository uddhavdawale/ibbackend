package org.ib.ibbackend.repository;

import org.ib.ibbackend.dto.CustomerInvoiceRecord;
import org.ib.ibbackend.entity.NonGstInvoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface NonGstInvoiceRepository extends JpaRepository<NonGstInvoice, Long> {

    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM NonGstInvoice i WHERE i.tenant.id = :tenantId")
    BigDecimal getTotalRevenueByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(i) FROM NonGstInvoice i WHERE i.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM NonGstInvoice i WHERE i.tenant.id = :tenantId AND i.date = CURRENT_DATE")
    BigDecimal getTodaySalesByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(i) FROM NonGstInvoice i WHERE i.tenant.id = :tenantId AND i.date = CURRENT_DATE")
    long countTodayInvoicesByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COALESCE(SUM(CASE WHEN i.grandTotal > i.paidAmount THEN i.grandTotal - i.paidAmount ELSE 0 END), 0) " +
            "FROM NonGstInvoice i WHERE i.tenant.id = :tenantId AND UPPER(i.status) <> 'CANCELLED'")
    BigDecimal getPendingAmountByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(i) FROM NonGstInvoice i WHERE i.tenant.id = :tenantId " +
            "AND UPPER(i.status) <> 'CANCELLED' AND i.grandTotal > i.paidAmount")
    long getPendingCountByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT i FROM NonGstInvoice i WHERE i.tenant.id = :tenantId")
    Page<NonGstInvoice> findByTenantId(@Param("tenantId") Long tenantId, Pageable pageable);

    @Query("SELECT i FROM NonGstInvoice i WHERE i.id = :id AND i.tenant.id = :tenantId")
    Optional<NonGstInvoice> findByIdAndTenantId(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM NonGstInvoice i WHERE i.id = :id AND i.tenant.id = :tenantId")
    Optional<NonGstInvoice> findByIdAndTenantIdForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Query("SELECT i FROM NonGstInvoice i WHERE i.tenant.id = :tenantId AND (LOWER(i.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(i.customerName) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<NonGstInvoice> findBySearchAndTenantId(@Param("tenantId") Long tenantId,
                                                @Param("search") String search,
                                                Pageable pageable);

    @Query("SELECT i FROM NonGstInvoice i WHERE i.tenant.id = :tenantId AND LOWER(i.customerPhone) = LOWER(:phone)")
    List<NonGstInvoice> findByTenantIdAndCustomerPhone(@Param("tenantId") Long tenantId,
                                                      @Param("phone") String phone);

    @Query("SELECT new org.ib.ibbackend.dto.CustomerInvoiceRecord(i.id, i.invoiceNo, i.customerName, i.customerPhone, i.grandTotal, i.paidAmount, i.date) " +
            "FROM NonGstInvoice i WHERE i.tenant.id = :tenantId")
    List<CustomerInvoiceRecord> findCustomerRecordsByTenantId(@Param("tenantId") Long tenantId);

    @Query(value = """
            SELECT COALESCE(SUM(GREATEST(COALESCE(i.grand_total, 0) - COALESCE(i.paid_amount, 0), 0)), 0)
            FROM non_gst_invoices i
            WHERE i.tenant_id = :tenantId
              AND UPPER(COALESCE(i.status, '')) <> 'CANCELLED'
              AND (NULLIF(BTRIM(i.customer_phone), '') = :phone
                   OR (NULLIF(BTRIM(i.customer_phone), '') IS NULL
                       AND NULLIF(BTRIM(i.customer_name), '') = :customerName))
            """, nativeQuery = true)
    BigDecimal getOutstandingByTenantAndPhone(@Param("tenantId") Long tenantId,
                                              @Param("phone") String phone,
                                              @Param("customerName") String customerName);

    @Query(value = """
            SELECT COALESCE(SUM(GREATEST(COALESCE(i.grand_total, 0) - COALESCE(i.paid_amount, 0), 0)), 0)
            FROM non_gst_invoices i
            WHERE i.tenant_id = :tenantId
              AND UPPER(COALESCE(i.status, '')) <> 'CANCELLED'
              AND NULLIF(BTRIM(i.customer_name), '') = :customerName
            """, nativeQuery = true)
    BigDecimal getOutstandingByTenantAndCustomerName(@Param("tenantId") Long tenantId,
                                                     @Param("customerName") String customerName);
}
