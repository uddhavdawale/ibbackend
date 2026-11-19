CREATE TABLE payment_records (
    id BIGSERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenants(id),
    invoice_id BIGINT REFERENCES invoices(id),
    non_gst_invoice_id BIGINT REFERENCES non_gst_invoices(id),
    reversal_of_payment_id BIGINT REFERENCES payment_records(id),
    collector_user_id BIGINT REFERENCES users(id),
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    method VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    reference_no VARCHAR(255),
    receipt_no VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'COLLECTION',
    collected_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_payment_records_one_invoice CHECK (
        (invoice_id IS NOT NULL AND non_gst_invoice_id IS NULL)
        OR (invoice_id IS NULL AND non_gst_invoice_id IS NOT NULL)
    ),
    CONSTRAINT uk_payment_records_tenant_receipt UNIQUE (tenant_id, receipt_no),
    CONSTRAINT uk_payment_records_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_payment_records_reversal
    ON payment_records (tenant_id, reversal_of_payment_id)
    WHERE reversal_of_payment_id IS NOT NULL;

CREATE INDEX idx_payment_records_tenant_collected
    ON payment_records (tenant_id, collected_at DESC);
CREATE INDEX idx_payment_records_tenant_collector_collected
    ON payment_records (tenant_id, collector_user_id, collected_at DESC);
CREATE INDEX idx_payment_records_tenant_method_collected
    ON payment_records (tenant_id, method, collected_at DESC);

INSERT INTO payment_records (
    tenant_id, invoice_id, amount, method, status, receipt_no,
    idempotency_key, source, collected_at
)
SELECT i.tenant_id, i.id, i.paid_amount,
       CASE
           WHEN UPPER(i.payment_method) LIKE 'CASH%' THEN 'CASH'
           WHEN UPPER(i.payment_method) LIKE 'UPI%' THEN 'UPI'
           WHEN UPPER(i.payment_method) LIKE 'CARD%' THEN 'CARD'
           WHEN UPPER(i.payment_method) LIKE 'BANK%' THEN 'BANK'
           WHEN UPPER(i.payment_method) LIKE 'WALLET%' THEN 'WALLET'
           ELSE 'OTHER'
       END,
       'RECEIVED', 'LEGACY-GST-' || i.id, 'LEGACY-GST-' || i.id,
       'LEGACY', COALESCE(i.date::timestamp AT TIME ZONE 'UTC', NOW())
FROM invoices i
WHERE i.paid_amount > 0
  AND i.tenant_id IS NOT NULL;

INSERT INTO payment_records (
    tenant_id, non_gst_invoice_id, amount, method, status, receipt_no,
    idempotency_key, source, collected_at
)
SELECT i.tenant_id, i.id, i.paid_amount,
       CASE
           WHEN UPPER(i.payment_method) LIKE 'CASH%' THEN 'CASH'
           WHEN UPPER(i.payment_method) LIKE 'UPI%' THEN 'UPI'
           WHEN UPPER(i.payment_method) LIKE 'CARD%' THEN 'CARD'
           WHEN UPPER(i.payment_method) LIKE 'BANK%' THEN 'BANK'
           WHEN UPPER(i.payment_method) LIKE 'WALLET%' THEN 'WALLET'
           ELSE 'OTHER'
       END,
       'RECEIVED', 'LEGACY-NONGST-' || i.id, 'LEGACY-NONGST-' || i.id,
       'LEGACY', COALESCE(i.invoice_date::timestamp AT TIME ZONE 'UTC', NOW())
FROM non_gst_invoices i
WHERE i.paid_amount > 0
  AND i.tenant_id IS NOT NULL;

CREATE TABLE cash_reconciliations (
    id BIGSERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenants(id),
    staff_user_id BIGINT NOT NULL REFERENCES users(id),
    reconciliation_date DATE NOT NULL,
    opening_float NUMERIC(12, 2) NOT NULL CHECK (opening_float >= 0),
    system_cash_total NUMERIC(12, 2) NOT NULL CHECK (system_cash_total >= 0),
    expected_cash NUMERIC(12, 2) NOT NULL,
    cash_refunds NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (cash_refunds >= 0),
    physical_cash_counted NUMERIC(12, 2) NOT NULL CHECK (physical_cash_counted >= 0),
    difference NUMERIC(12, 2) NOT NULL,
    remarks VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cash_reconciliations_tenant_date
    ON cash_reconciliations (tenant_id, reconciliation_date DESC);
