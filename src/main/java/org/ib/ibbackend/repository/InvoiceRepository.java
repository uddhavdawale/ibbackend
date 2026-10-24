package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    // Search by invoice number
    Page<Invoice> findByInvoiceNoContainingIgnoreCase(String invoiceNo, Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.tenant.id = :tenantId")
    Page<Invoice> findByTenantId(@Param("tenantId") Long tenantId, Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.id = :id AND i.tenant.id = :tenantId")
    java.util.Optional<Invoice> findByIdAndTenantId(@Param("id") Long id, @Param("tenantId") Long tenantId);

    // Total revenue (sum of grand_total)
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i")
    BigDecimal getTotalRevenue();

    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i WHERE i.tenant.id = :tenantId")
    BigDecimal getTotalRevenueByTenantId(@Param("tenantId") Long tenantId);

    // Today's sales - Use FUNCTION for DATE compatibility
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i " +
            "WHERE FUNCTION('DATE', i.date) = CURRENT_DATE")
    BigDecimal getTodaySales();

    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i WHERE i.tenant.id = :tenantId AND FUNCTION('DATE', i.date) = CURRENT_DATE")
    BigDecimal getTodaySalesByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(i) FROM Invoice i WHERE FUNCTION('DATE', i.date) = CURRENT_DATE")
    long countTodayInvoices();

    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.tenant.id = :tenantId AND FUNCTION('DATE', i.date) = CURRENT_DATE")
    long countTodayInvoicesByTenantId(@Param("tenantId") Long tenantId);

    // Pending invoices count
    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.status != 'PAID'")
    long getPendingInvoicesCount();

    // Pending amount
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i WHERE i.status != 'PAID'")
    BigDecimal getPendingAmount();

    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i WHERE i.tenant.id = :tenantId AND i.status != 'PAID'")
    BigDecimal getPendingAmountByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.tenant.id = :tenantId AND i.status != 'PAID'")
    long getPendingCountByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") Long tenantId);

    // Recent invoices (FIXED: Changed to Page<Invoice>)
    @Query("SELECT i FROM Invoice i ORDER BY i.date DESC")
    Page<Invoice> findRecentInvoices(Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.tenant.id = :tenantId ORDER BY i.date DESC")
    Page<Invoice> findRecentInvoicesByTenantId(@Param("tenantId") Long tenantId, Pageable pageable);

    // Search by invoice number OR customer name
    @Query("SELECT i FROM Invoice i WHERE " +
            "LOWER(i.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(i.customerName) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Invoice> findBySearch(@Param("search") String search, Pageable pageable);

    @Query("SELECT i FROM Invoice i WHERE i.tenant.id = :tenantId AND (LOWER(i.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(i.customerName) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Invoice> findBySearchAndTenantId(@Param("tenantId") Long tenantId,
                                         @Param("search") String search,
                                         Pageable pageable);
}