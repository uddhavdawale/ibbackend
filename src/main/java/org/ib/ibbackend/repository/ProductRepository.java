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

@Repository
public interface ProductRepository extends JpaRepository<Product,Long> {
    Page<Product> findAll(Pageable pageable);
    Page<Product> findByNameContainingIgnoreCaseOrSkuContainingIgnoreCase(
            String name, String sku, Pageable pageable);
    // Count low stock items (qty < minStock)
    @Query("SELECT COUNT(p) FROM Product p WHERE p.quantity < p.minStock")
    long countLowStockItems();

    // Find all products with expiry date (for dashboard)
    @Query("SELECT p FROM Product p WHERE p.expiryDate IS NOT NULL")
    List<Product> findAllWithExpiry();




    // ✅ SAFE - Search products
    @Query("SELECT p FROM Product p WHERE " +
            "LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
            "LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Product> findBySearch(@Param("search") String search, Pageable pageable);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.expiryDate IS NOT NULL AND p.expiryDate <= :threshold")
    long countExpiringSoon(@Param("threshold") LocalDate threshold);

    @Query("SELECT p FROM Product p WHERE p.tenant.id = :tenantId")
    Page<Product> findByTenant(@Param("tenantId") Long tenantId, Pageable pageable);

}
