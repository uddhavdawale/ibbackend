package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product,Long> {
    Page<Product> findAll(Pageable pageable);
    Page<Product> findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(
            String name, String sku, Pageable pageable);

    Optional<Product> findBySku(String sku);
    Optional<Product> findBySkuAndTenantId(String sku, Long tenantId);
    boolean existsBySku(String sku);
    boolean existsBySkuAndTenantId(String sku, Long tenantId);

    Page<Product> findByTenantId(Long tenantId, Pageable pageable);
    Optional<Product> findByIdAndTenantId(Long id, Long tenantId);

    // Count low stock items (qty < minStock)
    @Query("SELECT COUNT(p) FROM Product p WHERE p.quantity < p.minStock")
    long countLowStockItems();

    @Query("SELECT COUNT(p) FROM Product p WHERE p.tenant.id = :tenantId AND p.quantity < p.minStock")
    long countLowStockItemsByTenantId(@Param("tenantId") Long tenantId);

    // Find all products with expiry date (for dashboard)
    @Query("SELECT p FROM Product p WHERE p.expiryDate IS NOT NULL")
    List<Product> findAllWithExpiry();

    @Query("SELECT p FROM Product p WHERE p.tenant.id = :tenantId AND p.expiryDate IS NOT NULL")
    List<Product> findAllWithExpiryByTenantId(@Param("tenantId") Long tenantId);

    // ✅ SAFE - Search products
    @Query("SELECT p FROM Product p WHERE " +
            "LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Product> findBySearch(@Param("search") String search, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.tenant.id = :tenantId AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Product> findBySearchAndTenantId(@Param("tenantId") Long tenantId,
                                         @Param("search") String search,
                                         Pageable pageable);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.expiryDate IS NOT NULL AND p.expiryDate <= :threshold")
    long countExpiringSoon(@Param("threshold") LocalDate threshold);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.tenant.id = :tenantId AND p.expiryDate IS NOT NULL AND p.expiryDate <= :threshold")
    long countExpiringSoonByTenantId(@Param("tenantId") Long tenantId,
                                    @Param("threshold") LocalDate threshold);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.tenant.id = :tenantId")
    long countByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT p FROM Product p WHERE p.tenant.id = :tenantId")
    Page<Product> findByTenant(@Param("tenantId") Long tenantId, Pageable pageable);

}
