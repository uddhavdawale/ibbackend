CREATE INDEX IF NOT EXISTS idx_invoices_tenant_date_id
    ON invoices (tenant_id, date DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_non_gst_invoices_tenant_date_created_id
    ON non_gst_invoices (tenant_id, invoice_date, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_payment_records_tenant_invoice
    ON payment_records (tenant_id, invoice_id)
    WHERE invoice_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_records_tenant_non_gst_invoice
    ON payment_records (tenant_id, non_gst_invoice_id)
    WHERE non_gst_invoice_id IS NOT NULL;
