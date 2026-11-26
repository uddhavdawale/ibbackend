package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.ExchangeOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExchangeOrderRepository extends JpaRepository<ExchangeOrder, Long> {

    @Query("SELECT e FROM ExchangeOrder e WHERE e.tenant.id = :tenantId ORDER BY e.exchangeDate DESC")
    List<ExchangeOrder> findByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT e FROM ExchangeOrder e WHERE e.id = :id AND e.tenant.id = :tenantId")
    Optional<ExchangeOrder> findByIdAndTenantId(@Param("id") Long id, @Param("tenantId") Long tenantId);

    boolean existsByTenant_IdAndBillTypeAndInvoiceId(Long tenantId, String billType, Long invoiceId);

    @Query("SELECT item.productId, SUM(item.quantity) FROM ExchangeOrderItem item " +
            "WHERE item.exchangeOrder.invoiceId = :invoiceId " +
            "AND item.exchangeOrder.tenant.id = :tenantId " +
            "AND item.exchangeOrder.billType = :billType GROUP BY item.productId")
    List<Object[]> findExchangedQuantityTotals(@Param("invoiceId") Long invoiceId,
                                               @Param("tenantId") Long tenantId,
                                               @Param("billType") String billType);
}
