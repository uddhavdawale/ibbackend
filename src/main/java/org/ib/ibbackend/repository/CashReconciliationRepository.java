package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.CashReconciliation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashReconciliationRepository extends JpaRepository<CashReconciliation, Long> {
    @Query("SELECT r FROM CashReconciliation r WHERE r.tenant.id = :tenantId " +
            "AND (:staffUserId IS NULL OR r.staffUser.id = :staffUserId) " +
            "ORDER BY r.reconciliationDate DESC, r.id DESC")
    Page<CashReconciliation> findTenantHistory(@Param("tenantId") Integer tenantId,
                                                @Param("staffUserId") Long staffUserId,
                                                Pageable pageable);
}
