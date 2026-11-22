CREATE TABLE invoice_idempotency (
    tenant_id INTEGER NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(128) NOT NULL,
    invoice_type VARCHAR(16) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    invoice_id BIGINT,
    non_gst_invoice_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_invoice_idempotency PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT chk_invoice_idempotency_type CHECK (invoice_type IN ('GST', 'NON_GST')),
    CONSTRAINT chk_invoice_idempotency_invoice_reference CHECK (
        (invoice_id IS NULL AND non_gst_invoice_id IS NULL)
        OR (invoice_type = 'GST' AND invoice_id IS NOT NULL AND non_gst_invoice_id IS NULL)
        OR (invoice_type = 'NON_GST' AND non_gst_invoice_id IS NOT NULL AND invoice_id IS NULL)
    ),
    CONSTRAINT fk_invoice_idempotency_gst_invoice
        FOREIGN KEY (invoice_id) REFERENCES invoices(id) ON DELETE SET NULL,
    CONSTRAINT fk_invoice_idempotency_non_gst_invoice
        FOREIGN KEY (non_gst_invoice_id) REFERENCES non_gst_invoices(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uk_invoice_idempotency_gst_invoice
    ON invoice_idempotency(invoice_id) WHERE invoice_id IS NOT NULL;
CREATE UNIQUE INDEX uk_invoice_idempotency_non_gst_invoice
    ON invoice_idempotency(non_gst_invoice_id) WHERE non_gst_invoice_id IS NOT NULL;
