package org.ib.ibbackend.repository;

import org.ib.ibbackend.entity.CashHandover;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
import java.math.BigDecimal;

@Repository
public interface CashHandoverRepository extends JpaRepository<CashHandover, Long> {
    Optional<CashHandover> findByTenant_IdAndSender_IdAndIdempotencyKey(
            Integer tenantId, Long senderId, String idempotencyKey);

    Optional<CashHandover> findByTenant_IdAndReceiverConfirmationKey(
            Integer tenantId, String receiverConfirmationKey);

    Optional<CashHandover> findByTenant_IdAndResolutionIdempotencyKey(
            Integer tenantId, String resolutionIdempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM CashHandover h WHERE h.id = :id AND h.tenant.id = :tenantId")
    Optional<CashHandover> findByIdAndTenantIdForUpdate(
            @Param("id") Long id, @Param("tenantId") Integer tenantId);

    @Query("""
            SELECT h FROM CashHandover h
            WHERE h.id = :id AND h.tenant.id = :tenantId
              AND (:canViewAll = true OR h.sender.id = :userId OR h.recipient.id = :userId)
            """)
    Optional<CashHandover> findVisibleById(
            @Param("id") Long id,
            @Param("tenantId") Integer tenantId,
            @Param("userId") Long userId,
            @Param("canViewAll") boolean canViewAll);

    @Query("""
            SELECT h FROM CashHandover h
            WHERE h.tenant.id = :tenantId
              AND (:canViewAll = true OR h.sender.id = :userId OR h.recipient.id = :userId)
              AND (:status IS NULL OR h.status = :status)
            ORDER BY h.createdAt DESC, h.id DESC
            """)
    Page<CashHandover> findVisibleHistory(
            @Param("tenantId") Integer tenantId,
            @Param("userId") Long userId,
            @Param("canViewAll") boolean canViewAll,
            @Param("status") String status,
            Pageable pageable);

    @Query("""
            SELECT h FROM CashHandover h
            WHERE h.tenant.id = :tenantId
              AND LOWER(TRIM(h.recipient.role)) = 'store_admin'
              AND (:canViewAll = true OR h.sender.id = :userId OR h.recipient.id = :userId)
              AND (:status IS NULL OR h.status = :status)
            ORDER BY h.createdAt DESC, h.id DESC
            """)
    Page<CashHandover> findVisibleCollectionTickets(
            @Param("tenantId") Integer tenantId,
            @Param("userId") Long userId,
            @Param("canViewAll") boolean canViewAll,
            @Param("status") String status,
            Pageable pageable);

    @Query("""
            SELECT h FROM CashHandover h
            WHERE h.id = :id AND h.tenant.id = :tenantId
              AND LOWER(TRIM(h.recipient.role)) = 'store_admin'
              AND (:canViewAll = true OR h.sender.id = :userId OR h.recipient.id = :userId)
            """)
    Optional<CashHandover> findVisibleCollectionTicketById(
            @Param("id") Long id,
            @Param("tenantId") Integer tenantId,
            @Param("userId") Long userId,
            @Param("canViewAll") boolean canViewAll);

    @Query(value = """
            SELECT
              COALESCE((
                SELECT SUM(p.amount) FROM payment_records p
                WHERE p.tenant_id = :tenantId AND p.collector_user_id = :userId
                  AND p.method = 'CASH' AND p.status = 'RECEIVED'
                  AND p.source <> 'REFUND'
              ), 0)
              - COALESCE((
                SELECT SUM(p.amount) FROM payment_records p
                WHERE p.tenant_id = :tenantId AND p.collector_user_id = :userId
                  AND p.method = 'CASH' AND p.status = 'REFUNDED'
                  AND p.source = 'REFUND'
              ), 0)
              - COALESCE((
                SELECT SUM(CASE WHEN h.status = 'RESOLVED' THEN h.resolved_amount
                                WHEN h.status = 'DISPUTED'
                                  THEN LEAST(h.declared_amount, h.receiver_counted_amount)
                                ELSE h.declared_amount END)
                FROM cash_handovers h
                WHERE h.tenant_id = :tenantId AND h.sender_user_id = :userId
                  AND h.payment_method = 'CASH'
                  AND h.status IN ('PENDING', 'DISPUTED', 'ACCEPTED', 'RESOLVED')
              ), 0)
              + COALESCE((
                SELECT SUM(CASE WHEN h.status = 'ACCEPTED'
                                THEN h.receiver_counted_amount
                                WHEN h.status = 'RESOLVED' THEN h.resolved_amount
                                ELSE 0 END)
                FROM cash_handovers h
                WHERE h.tenant_id = :tenantId AND h.recipient_user_id = :userId
                  AND h.payment_method = 'CASH'
                  AND h.status IN ('ACCEPTED', 'RESOLVED')
              ), 0)
            """, nativeQuery = true)
    java.math.BigDecimal getAvailableCash(
            @Param("tenantId") Integer tenantId, @Param("userId") Long userId);

    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN status = 'RESOLVED'
                                    THEN resolved_amount ELSE receiver_counted_amount END), 0)
            FROM cash_handovers
            WHERE tenant_id = :tenantId AND recipient_user_id = :userId
              AND payment_method = 'CASH'
              AND ((status = 'ACCEPTED' AND recipient_confirmed_at >= :fromTime
                    AND recipient_confirmed_at < :toTime)
                OR (status = 'RESOLVED' AND resolved_at >= :fromTime AND resolved_at < :toTime))
            """, nativeQuery = true)
    BigDecimal sumIncomingCompletedForPeriod(
            @Param("tenantId") Integer tenantId,
            @Param("userId") Long userId,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime);

    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN status = 'RESOLVED'
                                    THEN resolved_amount ELSE declared_amount END), 0)
            FROM cash_handovers
            WHERE tenant_id = :tenantId AND sender_user_id = :userId
              AND payment_method = 'CASH'
              AND ((status = 'ACCEPTED' AND recipient_confirmed_at >= :fromTime
                    AND recipient_confirmed_at < :toTime)
                OR (status = 'RESOLVED' AND resolved_at >= :fromTime AND resolved_at < :toTime))
            """, nativeQuery = true)
    BigDecimal sumOutgoingCompletedForPeriod(
            @Param("tenantId") Integer tenantId,
            @Param("userId") Long userId,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime);
}
