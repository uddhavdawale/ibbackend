CREATE TABLE cash_tracking_configs (
    tenant_id INTEGER PRIMARY KEY REFERENCES tenants(id),
    tracking_started_at TIMESTAMPTZ NOT NULL,
    initialized_by_user_id BIGINT NOT NULL REFERENCES users(id),
    initialization_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_cash_tracking_initialization_key UNIQUE (tenant_id, initialization_key)
);

CREATE TABLE cash_opening_balances (
    tenant_id INTEGER NOT NULL REFERENCES tenants(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    opening_amount NUMERIC(12, 2) NOT NULL,
    approved_by_user_id BIGINT NOT NULL REFERENCES users(id),
    note VARCHAR(1000) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT uk_cash_opening_balance_key UNIQUE (tenant_id, idempotency_key)
);
