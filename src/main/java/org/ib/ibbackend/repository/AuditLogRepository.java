package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

		@Query("""
						SELECT a FROM AuditLog a
						WHERE (:tenantId < 0 OR a.tenantId = :tenantId)
							AND a.occurredAt >= :fromTs
							AND a.occurredAt <= :toTs
							AND (:actionType = '' OR LOWER(a.action) = LOWER(:actionType))
							AND (:outcome = '' OR LOWER(a.outcome) = LOWER(:outcome))
						ORDER BY a.occurredAt DESC
						""")
		Page<AuditLog> findForAuditView(@Param("tenantId") Long tenantId,
																		@Param("fromTs") Instant fromTs,
																		@Param("toTs") Instant toTs,
																		@Param("actionType") String actionType,
																		@Param("outcome") String outcome,
																		Pageable pageable);

		@Query("""
						SELECT COUNT(a) FROM AuditLog a
						WHERE (:tenantId < 0 OR a.tenantId = :tenantId)
							AND a.occurredAt >= :fromTs
							AND a.occurredAt <= :toTs
						""")
		long countForAuditScope(@Param("tenantId") Long tenantId,
														@Param("fromTs") Instant fromTs,
														@Param("toTs") Instant toTs);

		@Query("""
						SELECT COUNT(a) FROM AuditLog a
						WHERE (:tenantId < 0 OR a.tenantId = :tenantId)
							AND a.occurredAt >= :fromTs
							AND a.occurredAt <= :toTs
							AND LOWER(a.outcome) IN ('failure', 'denied')
						""")
		long countExceptionsForAuditScope(@Param("tenantId") Long tenantId,
																			@Param("fromTs") Instant fromTs,
																			@Param("toTs") Instant toTs);

		@Query("""
						SELECT COUNT(a) FROM AuditLog a
						WHERE (:tenantId < 0 OR a.tenantId = :tenantId)
							AND a.occurredAt >= :fromTs
							AND LOWER(a.action) IN :actions
						""")
		long countRecentEventsByActions(@Param("tenantId") Long tenantId,
																		@Param("fromTs") Instant fromTs,
																		@Param("actions") Collection<String> actions);
}
