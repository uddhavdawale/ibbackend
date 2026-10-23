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

    // Total revenue (sum of grand_total)
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i")
    BigDecimal getTotalRevenue();

    // Today's sales - Use FUNCTION for DATE compatibility
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i " +
            "WHERE FUNCTION('DATE', i.date) = CURRENT_DATE")
    BigDecimal getTodaySales();

    @Query("SELECT COUNT(i) FROM Invoice i WHERE FUNCTION('DATE', i.date) = CURRENT_DATE")
    long countTodayInvoices();

    // Pending invoices count
    @Query("SELECT COUNT(i) FROM Invoice i WHERE i.status != 'PAID'")
    long getPendingInvoicesCount();

    // Pending amount
    @Query("SELECT COALESCE(SUM(i.grandTotal), 0) FROM Invoice i WHERE i.status != 'PAID'")
    BigDecimal getPendingAmount();

    // Recent invoices (FIXED: Changed to Page<Invoice>)
    @Query("SELECT i FROM Invoice i ORDER BY i.date DESC")
    Page<Invoice> findRecentInvoices(Pageable pageable);

    // Search by invoice number OR customer name
    @Query("SELECT i FROM Invoice i WHERE " +
            "LOWER(i.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(i.customerName) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Invoice> findBySearch(@Param("search") String search, Pageable pageable);
}