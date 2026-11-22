package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.Tenant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {
    Optional<Tenant> findByNameIgnoreCase(String name);
    Optional<Tenant> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select t from Tenant t
            where :search is null or :search = ''
               or lower(t.name) like lower(concat('%', :search, '%'))
               or lower(t.code) like lower(concat('%', :search, '%'))
            """)
    Page<Tenant> searchForOrganizationAssignment(@Param("search") String search, Pageable pageable);
}
