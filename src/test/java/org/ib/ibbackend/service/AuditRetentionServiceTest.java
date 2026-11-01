package org.ib.ibbackend.service;

import org.ib.ibbackend.config.AuditRetentionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditRetentionServiceTest {

    @Test
    void executeRetentionCycle_shouldArchiveAndPurgeUsingBatchQueries() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuditRetentionProperties properties = new AuditRetentionProperties();
        properties.setEnabled(true);
        properties.setBatchSize(500);
        properties.setMaxBatchesPerRun(3);
        properties.setActiveRetentionDays(180);
        properties.setArchiveRetentionDays(730);

        when(jdbcTemplate.update(anyString(), any(), anyInt())).thenReturn(0);

        AuditRetentionService service = new AuditRetentionService(jdbcTemplate, properties);
        service.executeRetentionCycle(Instant.parse("2026-11-01T00:00:00Z"));

        verify(jdbcTemplate, times(2)).update(anyString(), any(), anyInt());
    }
}
