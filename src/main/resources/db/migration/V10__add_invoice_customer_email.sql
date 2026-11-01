ALTER TABLE IF EXISTS invoices
    ADD COLUMN IF NOT EXISTS customer_email VARCHAR(255);

-- Case-insensitive tenant-scoped lookup used by customer profile sync.
CREATE INDEX IF NOT EXISTS idx_invoices_tenant_customer_email_lower
    ON invoices (tenant_id, LOWER(customer_email));
