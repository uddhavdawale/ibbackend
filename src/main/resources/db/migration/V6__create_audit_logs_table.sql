CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    user_id BIGINT NULL,
    tenant_id BIGINT NULL,
    ip_address VARCHAR(64),
    user_agent VARCHAR(512),
    request_id VARCHAR(100),
    action VARCHAR(100) NOT NULL,
    resource_id VARCHAR(255),
    outcome VARCHAR(30) NOT NULL,
    detail VARCHAR(1000),
    request_path VARCHAR(255),
    http_method VARCHAR(10)
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_occurred_at ON audit_logs(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_logs_tenant_id ON audit_logs(tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_request_id ON audit_logs(request_id);
