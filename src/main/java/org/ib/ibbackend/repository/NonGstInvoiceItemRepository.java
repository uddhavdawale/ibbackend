package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.NonGstInvoiceItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NonGstInvoiceItemRepository extends JpaRepository<NonGstInvoiceItem, Long> {

		@Query("""
						SELECT ii.product.id, ii.name, ii.sku, SUM(ii.qty), COALESCE(SUM(ii.itemTotal), 0)
						FROM NonGstInvoiceItem ii
						JOIN ii.invoice inv
						WHERE inv.tenant.id = :tenantId
							AND UPPER(inv.status) <> :cancelledStatus
						GROUP BY ii.product.id, ii.name, ii.sku
						ORDER BY SUM(ii.qty) DESC, ii.name ASC
						""")
		List<Object[]> findTopSoldProductsByTenantId(@Param("tenantId") Long tenantId,
																									@Param("cancelledStatus") String cancelledStatus,
																									Pageable pageable);
}
