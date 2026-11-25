CREATE INDEX IF NOT EXISTS idx_invoices_tenant_customer_filter
    ON invoices (tenant_id, customer_phone, customer_name, invoice_no);

CREATE INDEX IF NOT EXISTS idx_non_gst_invoices_tenant_customer_filter
    ON non_gst_invoices (tenant_id, customer_phone, customer_name, invoice_no);

CREATE INDEX IF NOT EXISTS idx_customer_profiles_tenant_lookup
    ON customer_profiles (tenant_id, phone, customer_name, id);
