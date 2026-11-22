ALTER TABLE organizations
    ADD COLUMN state VARCHAR(100),
    ADD COLUMN gstin VARCHAR(15),
    ADD COLUMN phone VARCHAR(32),
    ADD COLUMN email VARCHAR(255);

ALTER TABLE invoices
    ADD COLUMN issuer_gstin VARCHAR(15),
    ADD COLUMN issuer_address VARCHAR(1000),
    ADD COLUMN issuer_phone VARCHAR(32),
    ADD COLUMN issuer_email VARCHAR(255);

ALTER TABLE non_gst_invoices
    ADD COLUMN issuer_gstin VARCHAR(15),
    ADD COLUMN issuer_address VARCHAR(1000),
    ADD COLUMN issuer_phone VARCHAR(32),
    ADD COLUMN issuer_email VARCHAR(255);
