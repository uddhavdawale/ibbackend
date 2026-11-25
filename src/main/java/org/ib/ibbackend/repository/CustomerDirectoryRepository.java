package org.ib.ibbackend.repository;

import org.ib.ibbackend.dto.CustomerDirectoryPageResponse.CustomerRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
public class CustomerDirectoryRepository {
    private static final String DIRECTORY_QUERY = """
            WITH totals AS (
                SELECT COUNT(*) AS total_elements,
                       COUNT(*) FILTER (WHERE COALESCE(due_balance, 0) > 0) AS due_customer_count
                FROM customer_profiles cp
                WHERE cp.tenant_id = :tenantId
                  AND (CAST(:searchPattern AS text) IS NULL
                       OR LOWER(COALESCE(cp.customer_name, '')) LIKE :searchPattern
                       OR LOWER(COALESCE(cp.phone, '')) LIKE :searchPattern
                       OR LOWER(COALESCE(cp.email, '')) LIKE :searchPattern
                       OR LOWER(COALESCE(cp.shop_name, '')) LIKE :searchPattern)
            ),
            page_profiles AS (
                SELECT cp.*
                FROM customer_profiles cp
                WHERE cp.tenant_id = :tenantId
                  AND (CAST(:searchPattern AS text) IS NULL
                       OR LOWER(COALESCE(cp.customer_name, '')) LIKE :searchPattern
                       OR LOWER(COALESCE(cp.phone, '')) LIKE :searchPattern
                       OR LOWER(COALESCE(cp.email, '')) LIKE :searchPattern
                       OR LOWER(COALESCE(cp.shop_name, '')) LIKE :searchPattern)
                ORDER BY cp.updated_at DESC, cp.id DESC
                LIMIT :limit OFFSET :offset
            )
            SELECT cp.id, cp.customer_name, cp.shop_name, cp.address, cp.gst_number, cp.phone, cp.email,
                   COALESCE((
                       SELECT SUM(GREATEST(COALESCE(i.grand_total, 0) - COALESCE(i.paid_amount, 0), 0))
                       FROM invoices i
                       WHERE i.tenant_id = cp.tenant_id
                         AND UPPER(COALESCE(i.status::text, '')) <> 'CANCELLED'
                         AND (
                             (NULLIF(BTRIM(cp.phone), '') IS NOT NULL
                              AND (NULLIF(BTRIM(i.customer_phone), '') = BTRIM(cp.phone)
                                   OR (NULLIF(BTRIM(i.customer_phone), '') IS NULL
                                       AND NULLIF(BTRIM(i.customer_name), '') = NULLIF(BTRIM(cp.customer_name), ''))))
                             OR (NULLIF(BTRIM(cp.phone), '') IS NULL
                                 AND NULLIF(BTRIM(i.customer_name), '') = NULLIF(BTRIM(cp.customer_name), ''))
                         )
                   ), 0)
                   + COALESCE((
                       SELECT SUM(GREATEST(COALESCE(i.grand_total, 0) - COALESCE(i.paid_amount, 0), 0))
                       FROM non_gst_invoices i
                       WHERE i.tenant_id = cp.tenant_id
                         AND UPPER(COALESCE(i.status, '')) <> 'CANCELLED'
                         AND (
                             (NULLIF(BTRIM(cp.phone), '') IS NOT NULL
                              AND (NULLIF(BTRIM(i.customer_phone), '') = BTRIM(cp.phone)
                                   OR (NULLIF(BTRIM(i.customer_phone), '') IS NULL
                                       AND NULLIF(BTRIM(i.customer_name), '') = NULLIF(BTRIM(cp.customer_name), ''))))
                             OR (NULLIF(BTRIM(cp.phone), '') IS NULL
                                 AND NULLIF(BTRIM(i.customer_name), '') = NULLIF(BTRIM(cp.customer_name), ''))
                         )
                   ), 0) AS current_due,
                   COALESCE(cp.previous_order_count, 0) AS previous_order_count,
                   cp.last_order_id, cp.last_order_no, cp.last_invoice_date,
                   totals.total_elements, totals.due_customer_count
            FROM totals
            LEFT JOIN page_profiles cp ON TRUE
            ORDER BY cp.updated_at DESC, cp.id DESC
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerDirectoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DirectoryPage findPage(Long tenantId, int page, int size, String search) {
        String normalizedSearch = search == null || search.isBlank()
                ? null
                : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("searchPattern", normalizedSearch)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        List<CustomerRow> content = new ArrayList<>();
        long[] totals = {0, 0};
        jdbc.query(DIRECTORY_QUERY, parameters, rs -> {
            totals[0] = rs.getLong("total_elements");
            totals[1] = rs.getLong("due_customer_count");
            if (rs.getObject("id") != null) {
                content.add(mapRow(rs));
            }
        });
        return new DirectoryPage(content, totals[0], totals[1]);
    }

    private CustomerRow mapRow(ResultSet rs) throws SQLException {
        BigDecimal dueBalance = rs.getBigDecimal("current_due");
        Date lastInvoiceDate = rs.getDate("last_invoice_date");
        Number orderId = (Number) rs.getObject("last_order_id");
        return new CustomerRow(
                rs.getString("id"),
                rs.getString("customer_name"),
                rs.getString("shop_name"),
                rs.getString("address"),
                rs.getString("gst_number"),
                rs.getString("phone"),
                rs.getString("email"),
                dueBalance == null ? BigDecimal.ZERO : dueBalance,
                rs.getInt("previous_order_count"),
                orderId == null ? null : orderId.longValue(),
                rs.getString("last_order_no"),
                lastInvoiceDate == null ? null : lastInvoiceDate.toLocalDate());
    }

    public record DirectoryPage(List<CustomerRow> content, long totalElements, long dueCustomerCount) {
    }
}
