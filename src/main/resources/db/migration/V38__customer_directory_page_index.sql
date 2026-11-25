CREATE INDEX IF NOT EXISTS idx_customer_profiles_tenant_updated_id
    ON customer_profiles (tenant_id, updated_at DESC, id DESC);
