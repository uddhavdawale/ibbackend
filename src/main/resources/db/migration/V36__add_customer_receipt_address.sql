ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS customer_address VARCHAR(500);

ALTER TABLE non_gst_invoices
    ADD COLUMN IF NOT EXISTS customer_address VARCHAR(500),
    ADD COLUMN IF NOT EXISTS place_of_supply_state VARCHAR(100);
