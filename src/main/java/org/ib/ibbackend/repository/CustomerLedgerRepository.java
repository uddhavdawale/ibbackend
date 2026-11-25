package org.ib.ibbackend.repository;

import org.ib.ibbackend.dto.CustomerLedgerCustomerOption;
import org.ib.ibbackend.dto.CustomerLedgerResponse;
import org.ib.ibbackend.dto.CustomerLedgerRow;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

@Repository
public class CustomerLedgerRepository {
    private static final String INVOICE_ROWS = """
            WITH invoice_rows AS (
                SELECT 'GST' AS bill_type, i.id, i.invoice_no, i.customer_name, i.customer_phone,
                       COALESCE(i.date::timestamp AT TIME ZONE 'Asia/Kolkata', TIMESTAMPTZ 'epoch') AS invoice_timestamp,
                       i.date AS invoice_day,
                       COALESCE(i.grand_total, 0) AS grand_total,
                       COALESCE(i.paid_amount, 0) AS paid_amount,
                       i.payment_method,
                       COALESCE(i.status::text, '') AS status,
                       %s AS account_key
                FROM invoices i
                WHERE i.tenant_id = :tenantId
                UNION ALL
                SELECT 'NON_GST' AS bill_type, i.id, i.invoice_no, i.customer_name, i.customer_phone,
                       i.created_at AT TIME ZONE 'Asia/Kolkata' AS invoice_timestamp,
                       i.invoice_date AS invoice_day,
                       COALESCE(i.grand_total, 0) AS grand_total,
                       COALESCE(i.paid_amount, 0) AS paid_amount,
                       i.payment_method,
                       COALESCE(i.status, '') AS status,
                       %s AS account_key
                FROM non_gst_invoices i
                WHERE i.tenant_id = :tenantId
            ),
            ledger_rows AS (
                SELECT r.*,
                       COALESCE(r.account_key, r.bill_type || ':' || r.id::text) AS account_partition_key,
                       CASE WHEN UPPER(r.status) = 'CANCELLED' THEN 0
                            ELSE GREATEST(r.grand_total - r.paid_amount, 0) END AS invoice_due
                FROM invoice_rows r
            ),
            matching_rows AS (
                SELECT r.*
                FROM ledger_rows r
                WHERE (CAST(:searchPattern AS text) IS NULL
                       OR LOWER(COALESCE(r.customer_name, '')) LIKE :searchPattern
                       OR LOWER(COALESCE(r.customer_phone, '')) LIKE :searchPattern
                       OR LOWER(COALESCE(r.invoice_no, '')) LIKE :searchPattern)
                  AND (CAST(:customerKey AS text) IS NULL OR r.account_key = :customerKey)
                  AND (CAST(:fromDate AS date) IS NULL OR r.invoice_day >= :fromDate)
                  AND (CAST(:toDate AS date) IS NULL OR r.invoice_day <= :toDate)
            )
            """;

    private static final String ACCOUNT_KEY = """
            COALESCE(
                (SELECT 'id:' || cp.id::text
                 FROM customer_profiles cp
                 WHERE cp.tenant_id = i.tenant_id
                   AND (
                       (NULLIF(BTRIM(i.customer_phone), '') IS NOT NULL
                        AND BTRIM(i.customer_phone) !~ '^0+$'
                        AND NULLIF(BTRIM(cp.phone), '') = BTRIM(i.customer_phone))
                       OR NULLIF(BTRIM(cp.customer_name), '') = NULLIF(BTRIM(i.customer_name), '')
                   )
                 ORDER BY CASE
                     WHEN NULLIF(BTRIM(i.customer_phone), '') IS NOT NULL
                          AND BTRIM(i.customer_phone) !~ '^0+$'
                          AND NULLIF(BTRIM(cp.phone), '') = BTRIM(i.customer_phone)
                     THEN 0 ELSE 1 END, cp.id
                 LIMIT 1),
                CASE
                    WHEN NULLIF(BTRIM(i.customer_phone), '') IS NOT NULL
                         AND BTRIM(i.customer_phone) !~ '^0+$'
                    THEN 'phone:' || BTRIM(i.customer_phone)
                    WHEN NULLIF(BTRIM(i.customer_name), '') IS NOT NULL
                    THEN 'name:' || BTRIM(i.customer_name)
                    ELSE NULL
                END
            )
            """;

    private static final String MATCH_FILTER = """
            (CAST(:searchPattern AS text) IS NULL
             OR LOWER(COALESCE(r.customer_name, '')) LIKE :searchPattern
             OR LOWER(COALESCE(r.customer_phone, '')) LIKE :searchPattern
             OR LOWER(COALESCE(r.invoice_no, '')) LIKE :searchPattern)
            AND (CAST(:customerKey AS text) IS NULL OR r.account_key = :customerKey)
            AND (CAST(:fromDate AS date) IS NULL OR r.invoice_day >= :fromDate)
            AND (CAST(:toDate AS date) IS NULL OR r.invoice_day <= :toDate)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerLedgerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CustomerLedgerRow> findPage(
            Integer tenantId, String search, String customerKey, LocalDate from, LocalDate to,
            long offset, int limit) {
        String sql = invoiceRowsCte() + """
                , relevant_accounts AS (
                    SELECT DISTINCT account_partition_key FROM matching_rows
                ),
                balances AS (
                    SELECT r.*,
                           SUM(r.invoice_due) OVER (
                               PARTITION BY r.account_partition_key
                               ORDER BY r.invoice_timestamp ASC, r.bill_type ASC, r.id ASC
                               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                           ) AS closing_balance
                    FROM ledger_rows r
                    JOIN relevant_accounts a USING (account_partition_key)
                )
                SELECT r.bill_type, r.id, r.invoice_no, r.customer_name, r.customer_phone,
                       r.invoice_timestamp, r.grand_total, r.paid_amount, r.invoice_due,
                       r.payment_method,
                       CASE
                           WHEN UPPER(r.status) = 'CANCELLED' THEN 'CANCELLED'
                           WHEN r.grand_total > 0 AND r.paid_amount >= r.grand_total THEN 'PAID'
                           WHEN r.paid_amount > 0 THEN 'PARTIAL'
                           ELSE 'PENDING'
                       END AS payment_status,
                       r.closing_balance - r.invoice_due AS opening_balance,
                       r.closing_balance
                FROM balances r
                WHERE %s
                ORDER BY r.invoice_timestamp DESC, r.bill_type ASC, r.id DESC
                LIMIT :limit OFFSET :offset
                """.formatted(MATCH_FILTER);
        MapSqlParameterSource parameters = filterParameters(tenantId, search, customerKey, from, to)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, parameters, this::mapLedgerRow);
    }

    public long countMatching(Integer tenantId, String search, String customerKey, LocalDate from, LocalDate to) {
        String sql = invoiceRowsCte() + " SELECT COUNT(*) FROM matching_rows";
        Long count = jdbc.queryForObject(sql, filterParameters(tenantId, search, customerKey, from, to), Long.class);
        return count == null ? 0 : count;
    }

    public CustomerLedgerResponse.Summary summarize(
            Integer tenantId, String search, String customerKey, LocalDate from, LocalDate to) {
        String sql = invoiceRowsCte() + """
                , matching_accounts AS (
                    SELECT DISTINCT account_partition_key FROM matching_rows
                ),
                matched_totals AS (
                    SELECT COALESCE(SUM(grand_total), 0) AS billed,
                           COALESCE(SUM(paid_amount), 0) AS received,
                           COUNT(DISTINCT account_key) AS customer_count
                    FROM matching_rows
                ),
                due_total AS (
                    SELECT COALESCE(SUM(r.invoice_due), 0) AS customer_due
                    FROM ledger_rows r
                    JOIN matching_accounts a USING (account_partition_key)
                    WHERE UPPER(r.status) <> 'CANCELLED'
                ),
                all_time_received AS (
                    SELECT COALESCE(SUM(CASE WHEN source = 'REFUND' THEN -amount ELSE amount END), 0) AS received
                    FROM payment_records
                    WHERE tenant_id = :tenantId AND status IN ('RECEIVED', 'REFUNDED')
                )
                SELECT m.billed,
                       CASE WHEN :unfiltered THEN a.received ELSE m.received END AS received,
                       CASE WHEN :unfiltered THEN (
                           SELECT COALESCE(SUM(invoice_due), 0)
                           FROM ledger_rows
                           WHERE UPPER(status) <> 'CANCELLED'
                       ) ELSE d.customer_due END AS customer_due,
                       m.customer_count
                FROM matched_totals m
                CROSS JOIN due_total d
                CROSS JOIN all_time_received a
                """;
        MapSqlParameterSource parameters = filterParameters(tenantId, search, customerKey, from, to)
                .addValue("unfiltered", isUnfiltered(search, customerKey, from, to));
        return jdbc.queryForObject(sql, parameters, (rs, rowNum) -> new CustomerLedgerResponse.Summary(
                money(rs, "billed"),
                money(rs, "received"),
                money(rs, "customer_due"),
                rs.getLong("customer_count")));
    }

    public List<CustomerLedgerCustomerOption> findCustomerOptions(Integer tenantId) {
        String sql = invoiceRowsCte() + """
                , candidates AS (
                    SELECT 'id:' || cp.id::text AS customer_key, cp.customer_name, cp.phone,
                           0 AS priority, cp.updated_at AS last_seen
                    FROM customer_profiles cp
                    WHERE cp.tenant_id = :tenantId
                    UNION ALL
                    SELECT account_key, customer_name, customer_phone, 1, invoice_timestamp
                    FROM ledger_rows
                    WHERE account_key IS NOT NULL
                ),
                selected AS (
                    SELECT DISTINCT ON (customer_key) customer_key, customer_name, phone
                    FROM candidates
                    ORDER BY customer_key, priority, last_seen DESC NULLS LAST
                )
                SELECT customer_key, customer_name, phone
                FROM selected
                ORDER BY LOWER(COALESCE(customer_name, '')), customer_key
                """;
        MapSqlParameterSource parameters = filterParameters(tenantId, null, null, null, null);
        return jdbc.query(sql, parameters,
                (rs, rowNum) -> new CustomerLedgerCustomerOption(
                        rs.getString("customer_key"), rs.getString("customer_name"), rs.getString("phone")));
    }

    public void streamMatchingRows(
            Integer tenantId, String search, String customerKey, LocalDate from, LocalDate to,
            Consumer<CustomerLedgerRow> consumer) {
        String sql = invoiceRowsCte() + """
                , relevant_accounts AS (
                    SELECT DISTINCT account_partition_key FROM matching_rows
                ),
                balances AS (
                    SELECT r.*,
                           SUM(r.invoice_due) OVER (
                               PARTITION BY r.account_partition_key
                               ORDER BY r.invoice_timestamp ASC, r.bill_type ASC, r.id ASC
                               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                           ) AS closing_balance
                    FROM ledger_rows r
                    JOIN relevant_accounts a USING (account_partition_key)
                )
                SELECT r.bill_type, r.id, r.invoice_no, r.customer_name, r.customer_phone,
                       r.invoice_timestamp, r.grand_total, r.paid_amount, r.invoice_due,
                       r.payment_method,
                       CASE
                           WHEN UPPER(r.status) = 'CANCELLED' THEN 'CANCELLED'
                           WHEN r.grand_total > 0 AND r.paid_amount >= r.grand_total THEN 'PAID'
                           WHEN r.paid_amount > 0 THEN 'PARTIAL'
                           ELSE 'PENDING'
                       END AS payment_status,
                       r.closing_balance - r.invoice_due AS opening_balance,
                       r.closing_balance
                FROM balances r
                WHERE %s
                ORDER BY r.invoice_timestamp DESC, r.bill_type ASC, r.id DESC
                """.formatted(MATCH_FILTER);
        MapSqlParameterSource parameters = filterParameters(tenantId, search, customerKey, from, to);
        ParsedSql parsedSql = NamedParameterUtils.parseSqlStatement(sql);
        String expandedSql = NamedParameterUtils.substituteNamedParameters(parsedSql, parameters);
        Object[] values = NamedParameterUtils.buildValueArray(parsedSql, parameters, null);
        jdbc.getJdbcTemplate().query(connection -> {
            PreparedStatement statement = connection.prepareStatement(expandedSql);
            statement.setFetchSize(500);
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement;
        }, rs -> {
            try {
                consumer.accept(mapLedgerRow(rs, 0));
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not read customer ledger export row", exception);
            }
        });
    }

    private String invoiceRowsCte() {
        return INVOICE_ROWS.formatted(ACCOUNT_KEY, ACCOUNT_KEY);
    }

    private MapSqlParameterSource filterParameters(
            Integer tenantId, String search, String customerKey, LocalDate from, LocalDate to) {
        String normalizedSearch = search == null || search.isBlank()
                ? null
                : "%" + escapeLike(search.trim()).toLowerCase(Locale.ROOT) + "%";
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("searchPattern", normalizedSearch)
                .addValue("customerKey", customerKey)
                .addValue("fromDate", from)
                .addValue("toDate", to);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private boolean isUnfiltered(String search, String customerKey, LocalDate from, LocalDate to) {
        return (search == null || search.isBlank()) && customerKey == null && from == null && to == null;
    }

    private CustomerLedgerRow mapLedgerRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp timestamp = rs.getTimestamp("invoice_timestamp");
        return new CustomerLedgerRow(
                rs.getString("bill_type") + ":" + rs.getLong("id"),
                rs.getString("bill_type"),
                rs.getString("invoice_no"),
                rs.getString("customer_name"),
                rs.getString("customer_phone"),
                timestamp == null ? null : timestamp.toInstant(),
                money(rs, "grand_total"),
                money(rs, "paid_amount"),
                money(rs, "invoice_due"),
                rs.getString("payment_method"),
                rs.getString("payment_status"),
                money(rs, "opening_balance"),
                money(rs, "closing_balance"));
    }

    private BigDecimal money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }
}
