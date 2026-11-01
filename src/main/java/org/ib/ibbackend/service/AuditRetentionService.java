package org.ib.ibbackend.service;

import org.ib.ibbackend.config.AuditRetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

@Service
@ConditionalOnProperty(value = "app.audit.retention.enabled", havingValue = "true", matchIfMissing = true)
public class AuditRetentionService {
    private static final Logger log = LoggerFactory.getLogger(AuditRetentionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuditRetentionProperties properties;

    public AuditRetentionService(JdbcTemplate jdbcTemplate, AuditRetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.audit.retention.cron:0 15 2 * * *}", zone = "${app.audit.retention.zone:UTC}")
    public void scheduledRetentionCycle() {
        executeRetentionCycle(Instant.now());
    }

    void executeRetentionCycle(Instant now) {
        int safeBatchSize = clamp(properties.getBatchSize(), 100, 10_000, 1000);
        int maxBatches = clamp(properties.getMaxBatchesPerRun(), 1, 200, 20);
        int activeDays = clamp(properties.getActiveRetentionDays(), 30, 3650, 180);
        int archiveDays = clamp(properties.getArchiveRetentionDays(), activeDays + 30, 3650, 730);

        if (archiveDays <= activeDays) {
            archiveDays = activeDays + 30;
        }

        Instant archiveBefore = now.minusSeconds((long) activeDays * 24L * 60L * 60L);
        Instant purgeBefore = now.minusSeconds((long) archiveDays * 24L * 60L * 60L);

        int archivedTotal = 0;
        int purgedTotal = 0;

        for (int i = 0; i < maxBatches; i++) {
            int moved = archiveBatch(archiveBefore, safeBatchSize);
            archivedTotal += moved;
            if (moved < safeBatchSize) {
                break;
            }
        }

        for (int i = 0; i < maxBatches; i++) {
            int deleted = purgeArchiveBatch(purgeBefore, safeBatchSize);
            purgedTotal += deleted;
            if (deleted < safeBatchSize) {
                break;
            }
        }

        log.info("Audit retention cycle completed: archived={}, purged={}, activeRetentionDays={}, archiveRetentionDays={}, batchSize={}, maxBatches={}",
                archivedTotal, purgedTotal, activeDays, archiveDays, safeBatchSize, maxBatches);
    }

    private int archiveBatch(Instant archiveBefore, int batchSize) {
        String sql = """
                WITH moved AS (
                    DELETE FROM audit_logs
                    WHERE id IN (
                        SELECT id FROM audit_logs
                        WHERE occurred_at < ?
                        ORDER BY occurred_at ASC
                        LIMIT ?
                    )
                    RETURNING id, occurred_at, user_id, actor_role, tenant_id, ip_address, user_agent, request_id, action, resource_type, resource_id, outcome, detail, request_path, http_method, metadata
                )
                INSERT INTO audit_logs_archive (
                    audit_id,
                    occurred_at,
                    user_id,
                    actor_role,
                    tenant_id,
                    ip_address,
                    user_agent,
                    request_id,
                    action,
                    resource_type,
                    resource_id,
                    outcome,
                    detail,
                    request_path,
                    http_method,
                    metadata,
                    archived_at
                )
                SELECT
                    id,
                    occurred_at,
                    user_id,
                    actor_role,
                    tenant_id,
                    ip_address,
                    user_agent,
                    request_id,
                    action,
                    resource_type,
                    resource_id,
                    outcome,
                    detail,
                    request_path,
                    http_method,
                    metadata,
                    NOW()
                FROM moved
                """;

        return jdbcTemplate.update(sql, Timestamp.from(archiveBefore), batchSize);
    }

    private int purgeArchiveBatch(Instant purgeBefore, int batchSize) {
        String sql = """
                DELETE FROM audit_logs_archive
                WHERE ctid IN (
                    SELECT ctid
                    FROM audit_logs_archive
                    WHERE archived_at < ?
                    ORDER BY archived_at ASC
                    LIMIT ?
                )
                """;

        return jdbcTemplate.update(sql, Timestamp.from(purgeBefore), batchSize);
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(Math.max(value, min), max);
    }
}
