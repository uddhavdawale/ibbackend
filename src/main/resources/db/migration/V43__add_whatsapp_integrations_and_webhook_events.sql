CREATE TABLE whatsapp_integrations (
    tenant_id INTEGER PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    phone_number_id VARCHAR(64) NOT NULL UNIQUE,
    waba_id VARCHAR(64) NOT NULL,
    display_phone_number VARCHAR(32) NOT NULL,
    access_token_ciphertext TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_whatsapp_phone_number_id_numeric CHECK (phone_number_id ~ '^[0-9]{1,64}$'),
    CONSTRAINT ck_whatsapp_waba_id_numeric CHECK (waba_id ~ '^[0-9]{1,64}$')
);

CREATE TABLE whatsapp_webhook_events (
    id BIGSERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phone_number_id VARCHAR(64) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    processing_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    last_error VARCHAR(120),
    CONSTRAINT uq_whatsapp_provider_event UNIQUE (phone_number_id, provider_event_id),
    CONSTRAINT ck_whatsapp_event_status CHECK (
        processing_status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')
    ),
    CONSTRAINT ck_whatsapp_event_type CHECK (
        event_type IN ('MESSAGE', 'DELIVERY_STATUS')
    )
);

CREATE INDEX idx_whatsapp_events_pending
    ON whatsapp_webhook_events (available_at, received_at)
    WHERE processing_status = 'PENDING';

CREATE INDEX idx_whatsapp_events_retention
    ON whatsapp_webhook_events (received_at);
