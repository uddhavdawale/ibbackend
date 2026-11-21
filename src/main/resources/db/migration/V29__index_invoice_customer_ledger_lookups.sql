CREATE INDEX IF NOT EXISTS idx_invoices_tenant_customer_phone_norm
    ON invoices (tenant_id, (NULLIF(BTRIM(customer_phone), '')));

CREATE INDEX IF NOT EXISTS idx_invoices_tenant_customer_name_norm
    ON invoices (tenant_id, (NULLIF(BTRIM(customer_name), '')));

CREATE INDEX IF NOT EXISTS idx_non_gst_invoices_tenant_customer_phone_norm
    ON non_gst_invoices (tenant_id, (NULLIF(BTRIM(customer_phone), '')));

CREATE INDEX IF NOT EXISTS idx_non_gst_invoices_tenant_customer_name_norm
    ON non_gst_invoices (tenant_id, (NULLIF(BTRIM(customer_name), '')));

CREATE INDEX IF NOT EXISTS idx_customer_profiles_tenant_phone
    ON customer_profiles (tenant_id, phone);

CREATE INDEX IF NOT EXISTS idx_customer_profiles_tenant_name
    ON customer_profiles (tenant_id, customer_name);
