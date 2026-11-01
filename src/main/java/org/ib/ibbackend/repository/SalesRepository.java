package org.ib.ibbackend.repository;



import org.ib.ibbackend.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;

public interface SalesRepository extends JpaRepository<Invoice, Long> {

    @Query(value = """
        SELECT COALESCE(SUM(i.grand_total), 0) 
        FROM invoices i 
        WHERE EXTRACT(YEAR FROM i.date) = :year 
          AND EXTRACT(MONTH FROM i.date) = :month
        """, nativeQuery = true)
    BigDecimal sumSalesByMonth(@Param("year") int year, @Param("month") int month);

    @Query(value = """
        SELECT COALESCE(SUM(i.grand_total), 0)
        FROM invoices i
        WHERE i.tenant_id = :tenantId
          AND EXTRACT(YEAR FROM i.date) = :year
          AND EXTRACT(MONTH FROM i.date) = :month
        """, nativeQuery = true)
    BigDecimal sumSalesByMonthAndTenant(@Param("tenantId") Long tenantId,
                                        @Param("year") int year,
                                        @Param("month") int month);
}