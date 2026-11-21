package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.CustomerProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {

    Optional<CustomerProfile> findByTenantIdAndPhone(Long tenantId, String phone);

    Optional<CustomerProfile> findByTenantIdAndEmail(Long tenantId, String email);

    Optional<CustomerProfile> findByTenantIdAndCustomerName(Long tenantId, String customerName);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CustomerProfile c WHERE c.tenantId = :tenantId AND c.phone = :phone")
    Optional<CustomerProfile> findByTenantIdAndPhoneForUpdate(@Param("tenantId") Long tenantId,
                                                              @Param("phone") String phone);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CustomerProfile c WHERE c.tenantId = :tenantId AND c.customerName = :customerName")
    Optional<CustomerProfile> findByTenantIdAndCustomerNameForUpdate(
            @Param("tenantId") Long tenantId, @Param("customerName") String customerName);

    List<CustomerProfile> findByTenantIdOrderByUpdatedAtDesc(Long tenantId);

    @Query("SELECT c FROM CustomerProfile c WHERE c.tenantId = :tenantId AND " +
            "(LOWER(c.customerName) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(c.phone) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(c.shopName) LIKE LOWER(CONCAT('%', :search, '%'))) ORDER BY c.updatedAt DESC")
    List<CustomerProfile> searchByTenantAndKeyword(@Param("tenantId") Long tenantId,
                                                  @Param("search") String search);
}
