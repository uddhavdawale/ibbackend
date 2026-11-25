INSERT INTO payment_records (
    tenant_id,
    invoice_id,
    amount,
    method,
    status,
    receipt_no,
    idempotency_key,
    source,
    reference_no,
    collected_at
)
SELECT i.tenant_id,
       i.id,
       i.paid_amount - COALESCE(recorded.net_amount, 0),
       CASE
           WHEN UPPER(COALESCE(i.payment_method, '')) LIKE 'CASH%' THEN 'CASH'
           WHEN UPPER(COALESCE(i.payment_method, '')) LIKE 'UPI%' THEN 'UPI'
           WHEN UPPER(COALESCE(i.payment_method, '')) LIKE 'CARD%' THEN 'CARD'
           WHEN UPPER(COALESCE(i.payment_method, '')) LIKE 'BANK%' THEN 'BANK'
           WHEN UPPER(COALESCE(i.payment_method, '')) LIKE 'WALLET%' THEN 'WALLET'
           ELSE 'OTHER'
       END,
       'RECEIVED',
       'RECON-GST-' || i.id,
       'RECON-GST-' || i.id,
       'LEGACY',
       'Reconciled from invoice paid balance',
       COALESCE(i.date::timestamp AT TIME ZONE 'Asia/Kolkata', NOW())
FROM invoices i
LEFT JOIN LATERAL (
    SELECT SUM(CASE WHEN p.source = 'REFUND' THEN -p.amount ELSE p.amount END) AS net_amount
    FROM payment_records p
    WHERE p.tenant_id = i.tenant_id
      AND p.invoice_id = i.id
      AND p.status IN ('RECEIVED', 'REFUNDED')
) recorded ON TRUE
WHERE i.tenant_id IS NOT NULL
  AND COALESCE(i.paid_amount, 0) > COALESCE(recorded.net_amount, 0)
ON CONFLICT (tenant_id, idempotency_key) DO NOTHING;

INSERT INTO payment_records (
    tenant_id,
    non_gst_invoice_id,
    amount,
    method,
    status,
    receipt_no,
    idempotency_key,
    source,
    reference_no,
    collected_at
)
SELECT i.tenant_id,
       i.id,
       i.paid_amount - COALESCE(recorded.net_amount, 0),
       CASE
           WHEN UPPER(COALESCE(i.payment_method, '')) LIKE 'CASH%' THEN 'CASH'
           WHEN UPPER(COALESCE(i.payment_method, '')) LIKE 'UPI%' THEN 'UPI'
           WHEN UPPER(COALESCE(i.payment_method, '')) LIKE 'CARD%' THEN 'CARD'
           WHEN UPPER(COALESCE(i.payment_method, '')) LIKE 'BANK%' THEN 'BANK'
           WHEN UPPER(COALESCE(i.payment_method, '')) LIKE 'WALLET%' THEN 'WALLET'
           ELSE 'OTHER'
       END,
       'RECEIVED',
       'RECON-NONGST-' || i.id,
       'RECON-NONGST-' || i.id,
       'LEGACY',
       'Reconciled from invoice paid balance',
       COALESCE(i.invoice_date::timestamp AT TIME ZONE 'Asia/Kolkata', NOW())
FROM non_gst_invoices i
LEFT JOIN LATERAL (
    SELECT SUM(CASE WHEN p.source = 'REFUND' THEN -p.amount ELSE p.amount END) AS net_amount
    FROM payment_records p
    WHERE p.tenant_id = i.tenant_id
      AND p.non_gst_invoice_id = i.id
      AND p.status IN ('RECEIVED', 'REFUNDED')
) recorded ON TRUE
WHERE i.tenant_id IS NOT NULL
  AND COALESCE(i.paid_amount, 0) > COALESCE(recorded.net_amount, 0)
ON CONFLICT (tenant_id, idempotency_key) DO NOTHING;
