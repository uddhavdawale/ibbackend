CREATE TABLE IF NOT EXISTS return_orders (
    id BIGSERIAL PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    bill_type VARCHAR(16) NOT NULL DEFAULT 'gst',
    invoice_no VARCHAR(255) NOT NULL,
    customer_name VARCHAR(255),
    customer_phone VARCHAR(255),
    return_date DATE NOT NULL DEFAULT CURRENT_DATE,
    reason VARCHAR(500),
    returned_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    tenant_id INTEGER NOT NULL REFERENCES tenants(id)
);

CREATE TABLE IF NOT EXISTS return_order_items (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12, 2) NOT NULL DEFAULT 0,
    line_total NUMERIC(12, 2) NOT NULL DEFAULT 0,
    return_order_id BIGINT NOT NULL REFERENCES return_orders(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS exchange_orders (
    id BIGSERIAL PRIMARY KEY,
    invoice_id BIGINT NOT NULL,
    bill_type VARCHAR(16) NOT NULL DEFAULT 'gst',
    invoice_no VARCHAR(255) NOT NULL,
    customer_name VARCHAR(255),
    customer_phone VARCHAR(255),
    exchange_date DATE NOT NULL DEFAULT CURRENT_DATE,
    reason VARCHAR(500),
    credit_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    new_invoice_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    tenant_id INTEGER NOT NULL REFERENCES tenants(id)
);

CREATE TABLE IF NOT EXISTS exchange_order_items (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12, 2) NOT NULL DEFAULT 0,
    line_total NUMERIC(12, 2) NOT NULL DEFAULT 0,
    exchange_order_id BIGINT NOT NULL REFERENCES exchange_orders(id) ON DELETE CASCADE
);

ALTER TABLE return_orders
    ADD COLUMN IF NOT EXISTS bill_type VARCHAR(16) NOT NULL DEFAULT 'gst';

ALTER TABLE exchange_orders
    ADD COLUMN IF NOT EXISTS bill_type VARCHAR(16) NOT NULL DEFAULT 'gst';

CREATE INDEX IF NOT EXISTS idx_return_orders_tenant_invoice
    ON return_orders (tenant_id, bill_type, invoice_id);

CREATE INDEX IF NOT EXISTS idx_return_order_items_order_product
    ON return_order_items (return_order_id, product_id);

CREATE INDEX IF NOT EXISTS idx_exchange_orders_tenant_invoice
    ON exchange_orders (tenant_id, bill_type, invoice_id);

CREATE INDEX IF NOT EXISTS idx_exchange_order_items_order_product
    ON exchange_order_items (exchange_order_id, product_id);
