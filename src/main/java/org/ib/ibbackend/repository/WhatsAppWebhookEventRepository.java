package org.ib.ibbackend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class WhatsAppWebhookEventRepository {
    private static final String INSERT_EVENT_SQL = """
            INSERT INTO whatsapp_webhook_events (
                tenant_id, phone_number_id, provider_event_id, event_type, payload
            )
            VALUES (?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (phone_number_id, provider_event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public WhatsAppWebhookEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(int tenantId, String phoneNumberId, String providerEventId, String eventType, String payload) {
        return jdbcTemplate.update(INSERT_EVENT_SQL,
                tenantId, phoneNumberId, providerEventId, eventType, payload);
    }

    @Transactional
    public List<QueuedEvent> claimBatch(int limit) {
        return jdbcTemplate.query("""
                WITH pending AS (
                    SELECT id
                    FROM whatsapp_webhook_events
                    WHERE processing_status = 'PENDING'
                      AND available_at <= CURRENT_TIMESTAMP
                    ORDER BY received_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE whatsapp_webhook_events AS event
                SET processing_status = 'PROCESSING',
                    attempt_count = attempt_count + 1
                FROM pending
                WHERE event.id = pending.id
                RETURNING event.id, event.tenant_id, event.phone_number_id,
                          event.provider_event_id, event.event_type, event.payload::text
                """,
                (resultSet, rowNumber) -> new QueuedEvent(
                        resultSet.getLong("id"),
                        resultSet.getInt("tenant_id"),
                        resultSet.getString("phone_number_id"),
                        resultSet.getString("provider_event_id"),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload")),
                limit);
    }

    public void markProcessed(long eventId) {
        jdbcTemplate.update("""
                UPDATE whatsapp_webhook_events
                SET processing_status = 'PROCESSED',
                    processed_at = CURRENT_TIMESTAMP,
                    last_error = NULL
                WHERE id = ? AND processing_status = 'PROCESSING'
                """, eventId);
    }

    public void markFailed(long eventId, String errorCode) {
        jdbcTemplate.update("""
                UPDATE whatsapp_webhook_events
                SET processing_status = CASE
                        WHEN attempt_count >= 5 THEN 'FAILED'
                        ELSE 'PENDING'
                    END,
                    available_at = CURRENT_TIMESTAMP
                        + make_interval(secs => LEAST(300, power(2, LEAST(attempt_count, 8))::integer)),
                    last_error = ?
                WHERE id = ? AND processing_status = 'PROCESSING'
                """, errorCode, eventId);
    }

    public int deleteExpiredEvents() {
        return jdbcTemplate.update("""
                DELETE FROM whatsapp_webhook_events
                WHERE received_at < CURRENT_TIMESTAMP - INTERVAL '30 days'
                """);
    }

    public int cancelPendingForTenant(int tenantId) {
        return jdbcTemplate.update("""
                UPDATE whatsapp_webhook_events
                SET processing_status = 'FAILED',
                    processed_at = CURRENT_TIMESTAMP,
                    last_error = 'INTEGRATION_DISCONNECTED'
                WHERE tenant_id = ?
                  AND processing_status IN ('PENDING', 'PROCESSING')
                """, tenantId);
    }

    public record QueuedEvent(
            long id,
            int tenantId,
            String phoneNumberId,
            String providerEventId,
            String eventType,
            String payload) {
    }
}
