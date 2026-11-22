CREATE TABLE hsn_tax_rates (
    hsn_code VARCHAR(8) PRIMARY KEY,
    gst_rate NUMERIC(5, 2) NOT NULL,
    CONSTRAINT chk_hsn_tax_rates_code CHECK (hsn_code ~ '^[0-9]{4}([0-9]{2}([0-9]{2})?)?$'),
    CONSTRAINT chk_hsn_tax_rates_rate CHECK (gst_rate >= 0 AND gst_rate <= 100)
);

ALTER TABLE invoices
    ADD COLUMN tax_pricing_mode VARCHAR(16),
    ADD COLUMN place_of_supply_state VARCHAR(100);

ALTER TABLE invoice_items
    ALTER COLUMN gst_rate TYPE NUMERIC(5, 2) USING gst_rate::NUMERIC(5, 2),
    ADD COLUMN entered_price NUMERIC(10, 2),
    ADD COLUMN tax_pricing_mode VARCHAR(16),
    ADD COLUMN cgst NUMERIC(10, 2),
    ADD COLUMN sgst NUMERIC(10, 2),
    ADD COLUMN igst NUMERIC(10, 2);
