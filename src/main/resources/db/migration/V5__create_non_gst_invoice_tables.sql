CREATE TABLE IF NOT EXISTS non_gst_invoices (
    id BIGSERIAL PRIMARY KEY,
    invoice_no VARCHAR(64) NOT NULL UNIQUE,
    customer_name VARCHAR(255) NOT NULL,
    customer_phone VARCHAR(50),
    invoice_date DATE NOT NULL,
    payment_method VARCHAR(100),
    payment_status VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    bill_type VARCHAR(32) NOT NULL,
    is_gst_invoice BOOLEAN NOT NULL DEFAULT FALSE,
    subtotal NUMERIC(12,2) NOT NULL DEFAULT 0,
    gst_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    grand_total NUMERIC(12,2) NOT NULL DEFAULT 0,
    paid_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    tenant_id INTEGER NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_non_gst_invoices_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_non_gst_invoices_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_non_gst_bill_type CHECK (LOWER(bill_type) = 'non-gst'),
    CONSTRAINT chk_non_gst_invoice_flag CHECK (is_gst_invoice = FALSE),
    CONSTRAINT chk_non_gst_gst_amount_zero CHECK (gst_amount = 0)
);

CREATE TABLE IF NOT EXISTS non_gst_invoice_items (
    id BIGSERIAL PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    sku VARCHAR(255),
    hsn_code VARCHAR(100),
    category VARCHAR(100),
    gst_rate INTEGER NOT NULL DEFAULT 0,
    cgst NUMERIC(12,2) NOT NULL DEFAULT 0,
    sgst NUMERIC(12,2) NOT NULL DEFAULT 0,
    igst NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    price NUMERIC(12,2) NOT NULL,
    qty INTEGER NOT NULL,
    item_subtotal NUMERIC(12,2) NOT NULL,
    item_total NUMERIC(12,2) NOT NULL,
    CONSTRAINT fk_non_gst_items_invoice FOREIGN KEY (invoice_id) REFERENCES non_gst_invoices(id) ON DELETE CASCADE,
    CONSTRAINT fk_non_gst_items_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT chk_non_gst_item_gst_rate CHECK (gst_rate = 0),
    CONSTRAINT chk_non_gst_item_tax_zero CHECK (cgst = 0 AND sgst = 0 AND igst = 0 AND tax_amount = 0)
);

CREATE INDEX IF NOT EXISTS idx_non_gst_invoices_tenant_id ON non_gst_invoices(tenant_id);
CREATE INDEX IF NOT EXISTS idx_non_gst_invoices_bill_type ON non_gst_invoices(bill_type);
CREATE INDEX IF NOT EXISTS idx_non_gst_invoice_items_invoice_id ON non_gst_invoice_items(invoice_id);
CREATE INDEX IF NOT EXISTS idx_non_gst_invoice_items_product_id ON non_gst_invoice_items(product_id);
