package org.ib.ibbackend.repository;


import org.ib.ibbackend.entity.InvoiceItem;
import org.ib.ibbackend.entity.Invoice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {

		@Query("""
						SELECT ii.productId, ii.name, ii.sku, SUM(ii.qty), COALESCE(SUM(ii.itemTotal), 0)
						FROM InvoiceItem ii
						JOIN ii.invoice inv
						WHERE inv.tenant.id = :tenantId
							AND inv.status <> :cancelledStatus
						GROUP BY ii.productId, ii.name, ii.sku
						ORDER BY SUM(ii.qty) DESC, ii.name ASC
						""")
		List<Object[]> findTopSoldProductsByTenantId(@Param("tenantId") Long tenantId,
																									@Param("cancelledStatus") Invoice.InvoiceStatus cancelledStatus,
																									Pageable pageable);
}