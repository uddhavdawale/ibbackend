package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.NonGstInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NonGstInvoiceRepository extends JpaRepository<NonGstInvoice, Long> {

    @Query("SELECT i FROM NonGstInvoice i WHERE i.tenant.id = :tenantId")
    Page<NonGstInvoice> findByTenantId(@Param("tenantId") Long tenantId, Pageable pageable);

    @Query("SELECT i FROM NonGstInvoice i WHERE i.id = :id AND i.tenant.id = :tenantId")
    Optional<NonGstInvoice> findByIdAndTenantId(@Param("id") Long id, @Param("tenantId") Long tenantId);

    @Query("SELECT i FROM NonGstInvoice i WHERE i.tenant.id = :tenantId AND (LOWER(i.invoiceNo) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(i.customerName) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<NonGstInvoice> findBySearchAndTenantId(@Param("tenantId") Long tenantId,
                                                @Param("search") String search,
                                                Pageable pageable);

    @Query("SELECT i FROM NonGstInvoice i WHERE i.tenant.id = :tenantId AND LOWER(i.customerPhone) = LOWER(:phone)")
    List<NonGstInvoice> findByTenantIdAndCustomerPhone(@Param("tenantId") Long tenantId,
                                                      @Param("phone") String phone);
}
