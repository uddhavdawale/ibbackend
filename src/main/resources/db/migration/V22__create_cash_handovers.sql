CREATE TABLE cash_handovers (
    id BIGSERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenants(id),
    sender_user_id BIGINT NOT NULL REFERENCES users(id),
    recipient_user_id BIGINT NOT NULL REFERENCES users(id),
    declared_amount NUMERIC(12, 2) NOT NULL CHECK (declared_amount > 0),
    receiver_counted_amount NUMERIC(12, 2) CHECK (receiver_counted_amount >= 0),
    resolved_amount NUMERIC(12, 2) CHECK (resolved_amount >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'DISPUTED', 'RESOLVED')),
    idempotency_key VARCHAR(100) NOT NULL,
    receiver_confirmation_key VARCHAR(100),
    resolution_idempotency_key VARCHAR(100),
    notes VARCHAR(1000),
    resolution_note VARCHAR(1000),
    sender_confirmed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    recipient_confirmed_at TIMESTAMPTZ,
    resolved_by_user_id BIGINT REFERENCES users(id),
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_cash_handovers_different_users CHECK (sender_user_id <> recipient_user_id),
    CONSTRAINT ck_cash_handovers_receiver_fields CHECK (
        (status = 'PENDING' AND receiver_counted_amount IS NULL AND recipient_confirmed_at IS NULL)
        OR (status <> 'PENDING' AND receiver_counted_amount IS NOT NULL AND recipient_confirmed_at IS NOT NULL)
    ),
    CONSTRAINT ck_cash_handovers_resolution_fields CHECK (
        (status = 'RESOLVED' AND resolved_amount IS NOT NULL
            AND resolved_by_user_id IS NOT NULL AND resolved_at IS NOT NULL
            AND resolution_note IS NOT NULL AND resolution_idempotency_key IS NOT NULL)
        OR (status <> 'RESOLVED' AND resolved_amount IS NULL
            AND resolved_by_user_id IS NULL AND resolved_at IS NULL)
    ),
    CONSTRAINT uk_cash_handovers_sender_idempotency
        UNIQUE (tenant_id, sender_user_id, idempotency_key),
    CONSTRAINT uk_cash_handovers_receiver_confirmation_key
        UNIQUE (tenant_id, receiver_confirmation_key),
    CONSTRAINT uk_cash_handovers_resolution_key
        UNIQUE (tenant_id, resolution_idempotency_key)
);

CREATE INDEX idx_cash_handovers_tenant_status_created
    ON cash_handovers (tenant_id, status, created_at DESC);
CREATE INDEX idx_cash_handovers_sender_status
    ON cash_handovers (tenant_id, sender_user_id, status);
CREATE INDEX idx_cash_handovers_recipient_status
    ON cash_handovers (tenant_id, recipient_user_id, status);
