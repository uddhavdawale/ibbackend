package org.ib.ibbackend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class CashBalanceLedgerRepository {
    public record OpeningBalance(BigDecimal amount, String note, String idempotencyKey) {}

    private final JdbcTemplate jdbcTemplate;

    public CashBalanceLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Instant findTrackingStartedAt(int tenantId) {
        List<Instant> values = jdbcTemplate.query(
                "SELECT tracking_started_at FROM cash_tracking_configs WHERE tenant_id = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), tenantId);
        return values.isEmpty() ? null : values.get(0);
    }

    public int ensureAutomaticTracking(int tenantId) {
        return jdbcTemplate.update("""
                INSERT INTO cash_tracking_configs
                    (tenant_id, tracking_started_at, initialized_by_user_id,
                     initialization_key, initialization_source)
                SELECT ?, COALESCE(MIN(u.created_at AT TIME ZONE 'UTC'), NOW()), NULL,
                       'AUTO-ONBOARDING-' || ?, 'SYSTEM_ONBOARDING'
                FROM users u
                WHERE u.tenant_id = ?
                  AND u.deleted_at IS NULL
                  AND COALESCE(u.disabled, FALSE) = FALSE
                  AND UPPER(COALESCE(u.approval_status, 'APPROVED')) = 'APPROVED'
                  AND LOWER(TRIM(COALESCE(u.role, ''))) IN ('billing_manager', 'store_manager')
                ON CONFLICT (tenant_id) DO NOTHING
                """, tenantId, tenantId, tenantId);
    }

    public OpeningBalance findOpeningBalance(int tenantId, long userId) {
        List<OpeningBalance> records = jdbcTemplate.query("""
                SELECT opening_amount, note, idempotency_key
                FROM cash_opening_balances WHERE tenant_id = ? AND user_id = ?
                """, (result, row) -> new OpeningBalance(
                result.getBigDecimal("opening_amount"),
                result.getString("note"),
                result.getString("idempotency_key")), tenantId, userId);
        return records.isEmpty() ? null : records.get(0);
    }

    public int initialize(int tenantId, long actorId, Instant trackingStartedAt, String key) {
        return jdbcTemplate.update("""
                INSERT INTO cash_tracking_configs
                    (tenant_id, tracking_started_at, initialized_by_user_id, initialization_key)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO NOTHING
                """, tenantId, Timestamp.from(trackingStartedAt), actorId, key);
    }

    public Instant findTrackingStartedAtForKey(int tenantId, String key) {
        List<Instant> values = jdbcTemplate.query(
                "SELECT tracking_started_at FROM cash_tracking_configs WHERE tenant_id = ? AND initialization_key = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), tenantId, key);
        return values.isEmpty() ? null : values.get(0);
    }

    public int insertOpeningBalance(int tenantId, long userId, long actorId, BigDecimal amount,
                                   String note, String key) {
        return jdbcTemplate.update("""
                INSERT INTO cash_opening_balances
                    (tenant_id, user_id, approved_by_user_id, opening_amount, note, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, tenantId, userId, actorId, amount, note, key);
    }

    public List<Map<String, Object>> findBalances(int tenantId, Instant trackingStartedAt, Instant asOf) {
        return jdbcTemplate.query("""
                WITH params AS (
                    SELECT CAST(? AS TIMESTAMPTZ) AS tracking_start, CAST(? AS TIMESTAMPTZ) AS as_of
                ),
                cashiers AS (
                    SELECT u.id, COALESCE(NULLIF(TRIM(u.owner_name), ''),
                           NULLIF(TRIM(u.username), ''), u.email) AS user_name,
                           LOWER(TRIM(u.role)) AS role
                    FROM users u
                    WHERE u.tenant_id = ?
                      AND u.deleted_at IS NULL
                      AND COALESCE(u.disabled, FALSE) = FALSE
                      AND UPPER(COALESCE(u.approval_status, 'APPROVED')) = 'APPROVED'
                      AND LOWER(TRIM(COALESCE(u.role, ''))) IN ('billing_manager', 'store_manager')
                ),
                last_settled AS (
                    SELECT h.sender_user_id AS user_id,
                           MAX(CASE WHEN h.status = 'ACCEPTED'
                                    THEN h.recipient_confirmed_at ELSE h.resolved_at END) AS settled_at
                    FROM cash_handovers h CROSS JOIN params p
                    WHERE h.tenant_id = ? AND h.status IN ('ACCEPTED', 'RESOLVED')
                      AND (CASE WHEN h.status = 'ACCEPTED' THEN h.recipient_confirmed_at
                                ELSE h.resolved_at END) >= p.tracking_start
                      AND (CASE WHEN h.status = 'ACCEPTED' THEN h.recipient_confirmed_at
                                ELSE h.resolved_at END) <= p.as_of
                    GROUP BY h.sender_user_id
                ),
                periods AS (
                    SELECT c.id AS user_id, c.user_name, c.role,
                           COALESCE(ls.settled_at, p.tracking_start) AS period_start,
                           p.tracking_start, p.as_of
                    FROM cashiers c CROSS JOIN params p
                    LEFT JOIN last_settled ls ON ls.user_id = c.id
                ),
                payments AS (
                    SELECT pe.user_id,
                           COUNT(*) FILTER (WHERE r.method = 'CASH' AND r.status = 'RECEIVED'
                                AND r.source <> 'REFUND' AND r.collected_at >= pe.period_start
                                AND r.collected_at <= pe.as_of) AS period_payment_count,
                           COALESCE(SUM(CASE WHEN r.method = 'CASH' AND r.status = 'RECEIVED'
                                AND r.source <> 'REFUND' AND r.collected_at >= pe.period_start
                                AND r.collected_at <= pe.as_of THEN r.amount ELSE 0 END), 0) AS period_collected,
                           COALESCE(SUM(CASE WHEN r.method = 'CASH' AND r.status = 'REFUNDED'
                                AND r.source = 'REFUND' AND r.collected_at >= pe.period_start
                                AND r.collected_at <= pe.as_of THEN r.amount ELSE 0 END), 0) AS period_refunded,
                           COALESCE(SUM(CASE WHEN r.method = 'CASH' AND r.status = 'RECEIVED'
                                AND r.source <> 'REFUND' AND r.collected_at < pe.period_start THEN r.amount
                                WHEN r.method = 'CASH' AND r.status = 'REFUNDED'
                                AND r.source = 'REFUND' AND r.collected_at < pe.period_start THEN -r.amount
                                ELSE 0 END), 0) AS net_before_period
                    FROM periods pe
                    LEFT JOIN payment_records r ON r.tenant_id = ? AND r.collector_user_id = pe.user_id
                        AND r.collected_at >= pe.tracking_start AND r.collected_at <= pe.as_of
                    GROUP BY pe.user_id
                ),
                outgoing AS (
                    SELECT pe.user_id,
                           COALESCE(SUM(CASE WHEN h.status = 'ACCEPTED' THEN h.declared_amount
                                             WHEN h.status = 'RESOLVED' THEN h.resolved_amount
                                             ELSE 0 END), 0) AS completed_outgoing,
                           COALESCE(SUM(CASE WHEN (CASE WHEN h.status = 'ACCEPTED'
                                                       THEN h.recipient_confirmed_at ELSE h.resolved_at END)
                                                     <= pe.period_start
                                             THEN CASE WHEN h.status = 'ACCEPTED' THEN h.declared_amount
                                                       WHEN h.status = 'RESOLVED' THEN h.resolved_amount
                                                       ELSE 0 END ELSE 0 END), 0) AS outgoing_before_period,
                           COALESCE(SUM(CASE WHEN h.payment_method = 'CASH' AND h.status = 'PENDING'
                                             THEN h.declared_amount ELSE 0 END), 0)
                                AS pending_amount,
                           COALESCE(SUM(CASE WHEN h.payment_method = 'CASH' AND h.status = 'DISPUTED'
                                             THEN LEAST(h.declared_amount, h.receiver_counted_amount)
                                             ELSE 0 END), 0) AS disputed_amount
                    FROM periods pe
                    LEFT JOIN cash_handovers h ON h.tenant_id = ? AND h.sender_user_id = pe.user_id
                        AND ((h.status IN ('ACCEPTED', 'RESOLVED')
                              AND (CASE WHEN h.status = 'ACCEPTED' THEN h.recipient_confirmed_at
                                        ELSE h.resolved_at END) >= pe.tracking_start
                              AND (CASE WHEN h.status = 'ACCEPTED' THEN h.recipient_confirmed_at
                                        ELSE h.resolved_at END) <= pe.as_of)
                             OR h.status IN ('PENDING', 'DISPUTED'))
                    GROUP BY pe.user_id
                ),
                incoming AS (
                    SELECT pe.user_id,
                           COALESCE(SUM(CASE WHEN h.status = 'ACCEPTED' THEN h.declared_amount
                                             WHEN h.status = 'RESOLVED' THEN h.resolved_amount
                                             ELSE 0 END), 0) AS completed_incoming,
                           COALESCE(SUM(CASE WHEN (CASE WHEN h.status = 'ACCEPTED'
                                                       THEN h.recipient_confirmed_at ELSE h.resolved_at END)
                                                     <= pe.period_start
                                             THEN CASE WHEN h.status = 'ACCEPTED' THEN h.declared_amount
                                                       WHEN h.status = 'RESOLVED' THEN h.resolved_amount
                                                       ELSE 0 END ELSE 0 END), 0) AS incoming_before_period
                    FROM periods pe
                    LEFT JOIN cash_handovers h ON h.tenant_id = ? AND h.recipient_user_id = pe.user_id
                        AND h.payment_method = 'CASH'
                        AND h.status IN ('ACCEPTED', 'RESOLVED')
                        AND (CASE WHEN h.status = 'ACCEPTED' THEN h.recipient_confirmed_at
                                  ELSE h.resolved_at END) >= pe.tracking_start
                        AND (CASE WHEN h.status = 'ACCEPTED' THEN h.recipient_confirmed_at
                                  ELSE h.resolved_at END) <= pe.as_of
                    GROUP BY pe.user_id
                )
                SELECT pe.user_id, pe.user_name, pe.role, pe.tracking_start, pe.period_start, pe.as_of,
                       ls.settled_at AS last_settled_at,
                       COALESCE(ob.opening_amount, 0) AS opening_amount,
                       p.period_payment_count, p.period_collected, p.period_refunded,
                       p.net_before_period, o.completed_outgoing, o.outgoing_before_period,
                       o.pending_amount, o.disputed_amount,
                       i.completed_incoming, i.incoming_before_period
                FROM periods pe
                LEFT JOIN last_settled ls ON ls.user_id = pe.user_id
                LEFT JOIN cash_opening_balances ob ON ob.tenant_id = ? AND ob.user_id = pe.user_id
                LEFT JOIN payments p ON p.user_id = pe.user_id
                LEFT JOIN outgoing o ON o.user_id = pe.user_id
                LEFT JOIN incoming i ON i.user_id = pe.user_id
                ORDER BY pe.user_name, pe.user_id
                """, (result, row) -> mapBalanceRow(result),
                Timestamp.from(trackingStartedAt), Timestamp.from(asOf),
                tenantId, tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    private Map<String, Object> mapBalanceRow(ResultSet result) throws SQLException {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("userId", result.getLong("user_id"));
        row.put("userName", result.getString("user_name"));
        row.put("role", result.getString("role"));
        row.put("trackingStartedAt", result.getTimestamp("tracking_start").toInstant());
        row.put("periodStart", result.getTimestamp("period_start").toInstant());
        java.sql.Timestamp settledAt = result.getTimestamp("last_settled_at");
        row.put("lastSettledHandoverAt", settledAt == null ? null : settledAt.toInstant());
        row.put("asOf", result.getTimestamp("as_of").toInstant());

        BigDecimal opening = result.getBigDecimal("opening_amount");
        BigDecimal periodCollected = result.getBigDecimal("period_collected");
        BigDecimal periodRefunded = result.getBigDecimal("period_refunded");
        BigDecimal carried = opening.add(result.getBigDecimal("net_before_period"))
                .subtract(result.getBigDecimal("outgoing_before_period"))
                .add(result.getBigDecimal("incoming_before_period"));
        BigDecimal due = opening.add(result.getBigDecimal("period_collected"))
                .subtract(result.getBigDecimal("period_refunded"))
                .add(result.getBigDecimal("net_before_period"))
                .subtract(result.getBigDecimal("completed_outgoing"))
                .add(result.getBigDecimal("completed_incoming"));
        BigDecimal pending = result.getBigDecimal("pending_amount");
        BigDecimal disputed = result.getBigDecimal("disputed_amount");
        row.put("cashPaymentCountSinceLastHandover", result.getLong("period_payment_count"));
        row.put("cashCollectedSinceLastHandover", periodCollected);
        row.put("cashRefundedSinceLastHandover", periodRefunded);
        row.put("carriedForwardDue", carried.max(BigDecimal.ZERO));
        row.put("dueToSubmit", due.max(BigDecimal.ZERO));
        row.put("pendingHandoverAmount", pending);
        row.put("disputedHandoverAmount", disputed);
        row.put("availableToSubmit", due.subtract(pending).subtract(disputed).max(BigDecimal.ZERO));
        row.put("cashCreditBalance", due.negate().max(BigDecimal.ZERO));
        return row;
    }
}
