package org.ib.ibbackend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AuthFlowRetentionService {
    private static final int CLEANUP_BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;

    public AuthFlowRetentionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${app.security.auth-flow.cleanup-delay-ms:3600000}")
    public void cleanupExpiredRecords() {
        jdbcTemplate.update("""
                DELETE FROM auth_challenges
                WHERE id IN (
                    SELECT id FROM auth_challenges
                    WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
                    ORDER BY expires_at
                    LIMIT ?
                )
                """, CLEANUP_BATCH_SIZE);
        jdbcTemplate.update("""
                DELETE FROM password_reset_tokens
                WHERE id IN (
                    SELECT id FROM password_reset_tokens
                    WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
                    ORDER BY expires_at
                    LIMIT ?
                )
                """, CLEANUP_BATCH_SIZE);
    }
}
