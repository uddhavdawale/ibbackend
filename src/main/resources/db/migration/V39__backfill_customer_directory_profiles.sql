WITH invoice_rows AS (
    SELECT i.tenant_id, i.id, i.invoice_no, i.customer_name, i.customer_phone, i.customer_email,
           i.grand_total, i.paid_amount, i.date AS invoice_date, i.status::text AS status
    FROM invoices i
    WHERE i.tenant_id IS NOT NULL
    UNION ALL
    SELECT i.tenant_id, i.id, i.invoice_no, i.customer_name, i.customer_phone, NULL AS customer_email,
           i.grand_total, i.paid_amount, i.invoice_date, i.status
    FROM non_gst_invoices i
    WHERE i.tenant_id IS NOT NULL
),
identifiable_rows AS (
    SELECT r.*,
           CASE
               WHEN NULLIF(BTRIM(r.customer_phone), '') IS NOT NULL
                    AND BTRIM(r.customer_phone) !~ '^0+$'
               THEN 'phone:' || BTRIM(r.customer_phone)
               WHEN NULLIF(BTRIM(r.customer_name), '') IS NOT NULL
               THEN 'name:' || BTRIM(r.customer_name)
               ELSE NULL
           END AS account_key
    FROM invoice_rows r
),
accounts AS (
    SELECT tenant_id, account_key,
           (ARRAY_AGG(customer_name ORDER BY invoice_date DESC NULLS LAST, id DESC))[1] AS customer_name,
           CASE WHEN account_key LIKE 'name:%' THEN NULL
                ELSE SUBSTRING(account_key FROM 7) END AS phone,
           (ARRAY_AGG(customer_email ORDER BY invoice_date DESC NULLS LAST, id DESC))[1] AS email,
           SUM(CASE WHEN UPPER(COALESCE(status, '')) = 'CANCELLED' THEN 0
                    ELSE GREATEST(COALESCE(grand_total, 0) - COALESCE(paid_amount, 0), 0)
               END) AS due_balance,
           COUNT(*)::integer AS previous_order_count,
           (ARRAY_AGG(id ORDER BY invoice_date DESC NULLS LAST, id DESC))[1] AS last_order_id,
           (ARRAY_AGG(invoice_no ORDER BY invoice_date DESC NULLS LAST, id DESC))[1] AS last_order_no,
           MAX(invoice_date) AS last_invoice_date,
           MIN(invoice_date)::timestamp AS created_at,
           MAX(invoice_date)::timestamp AS updated_at
    FROM identifiable_rows
    WHERE account_key IS NOT NULL
    GROUP BY tenant_id, account_key
)
INSERT INTO customer_profiles (
    tenant_id, customer_name, phone, email,
    due_balance, balance_due, outstanding_balance, pending_amount, pending_balance,
    amount_due, total_due, balance, receivable, outstanding,
    previous_order_count, last_order_id, last_order_no, last_invoice_date,
    created_at, updated_at
)
SELECT a.tenant_id, a.customer_name, a.phone, a.email,
       a.due_balance, a.due_balance, a.due_balance, a.due_balance, a.due_balance,
       a.due_balance, a.due_balance, a.due_balance, a.due_balance, a.due_balance,
       a.previous_order_count, a.last_order_id, a.last_order_no, a.last_invoice_date,
       COALESCE(a.created_at, NOW()), COALESCE(a.updated_at, NOW())
FROM accounts a
WHERE NOT EXISTS (
    SELECT 1
    FROM customer_profiles cp
    WHERE cp.tenant_id = a.tenant_id
      AND (
          (a.phone IS NOT NULL AND NULLIF(BTRIM(cp.phone), '') = a.phone)
          OR NULLIF(BTRIM(cp.customer_name), '') =
             NULLIF(BTRIM(a.customer_name), '')
      )
);
