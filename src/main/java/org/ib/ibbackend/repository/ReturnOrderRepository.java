package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.ReturnOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, Long> {

    @Query("SELECT r FROM ReturnOrder r WHERE r.tenant.id = :tenantId ORDER BY r.returnDate DESC")
    List<ReturnOrder> findByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT r FROM ReturnOrder r WHERE r.id = :id AND r.tenant.id = :tenantId")
    Optional<ReturnOrder> findByIdAndTenantId(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
