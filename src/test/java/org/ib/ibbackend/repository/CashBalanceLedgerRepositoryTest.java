package org.ib.ibbackend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CashBalanceLedgerRepositoryTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CashBalanceLedgerRepository repository =
            new CashBalanceLedgerRepository(jdbcTemplate);

    @Test
    @SuppressWarnings("unchecked")
    void settlesDigitalOutgoingWithoutAddingItToPhysicalCash() {
        repository.findBalances(7, Instant.now(), Instant.now());

        org.mockito.ArgumentCaptor<String> sqlCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));

        String sql = sqlCaptor.getValue();
        String lastSettled = section(sql, "last_settled AS (", "periods AS (");
        String outgoing = section(sql, "outgoing AS (", "incoming AS (");
        String incoming = section(sql, "incoming AS (", "SELECT pe.user_id, pe.user_name");

        assertFalse(lastSettled.contains("payment_method = 'CASH'"));
        assertFalse(outgoing.contains("AND h.payment_method = 'CASH'"));
        assertTrue(outgoing.contains("WHEN h.status = 'ACCEPTED' THEN h.declared_amount"));
        assertTrue(outgoing.contains("WHEN h.payment_method = 'CASH' AND h.status = 'PENDING'"));
        assertTrue(incoming.contains("AND h.payment_method = 'CASH'"));
    }

    private String section(String sql, String startMarker, String endMarker) {
        int start = sql.indexOf(startMarker);
        int end = sql.indexOf(endMarker, start + startMarker.length());
        return sql.substring(start, end);
    }
}
