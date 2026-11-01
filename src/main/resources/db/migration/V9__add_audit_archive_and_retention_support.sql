ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS metadata VARCHAR(4000),
    ADD COLUMN IF NOT EXISTS actor_role VARCHAR(80),
    ADD COLUMN IF NOT EXISTS resource_type VARCHAR(100);

CREATE TABLE IF NOT EXISTS audit_logs_archive (
    archive_id BIGSERIAL PRIMARY KEY,
    audit_id BIGINT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    user_id BIGINT NULL,
    actor_role VARCHAR(80),
    tenant_id BIGINT NULL,
    ip_address VARCHAR(64),
    user_agent VARCHAR(512),
    request_id VARCHAR(100),
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100),
    resource_id VARCHAR(255),
    outcome VARCHAR(30) NOT NULL,
    detail VARCHAR(1000),
    request_path VARCHAR(255),
    http_method VARCHAR(10),
    metadata VARCHAR(4000),
    archived_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_archive_occurred_at ON audit_logs_archive(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_archive_archived_at ON audit_logs_archive(archived_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_logs_archive_tenant_id ON audit_logs_archive(tenant_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_archive_action ON audit_logs_archive(action);
CREATE INDEX IF NOT EXISTS idx_audit_logs_archive_request_id ON audit_logs_archive(request_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_archive_resource_type ON audit_logs_archive(resource_type);

CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_role ON audit_logs(actor_role);
CREATE INDEX IF NOT EXISTS idx_audit_logs_resource_type ON audit_logs(resource_type);
