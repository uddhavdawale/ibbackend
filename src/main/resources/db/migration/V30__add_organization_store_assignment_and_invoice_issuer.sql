CREATE TABLE organizations (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    city VARCHAR(100),
    pincode VARCHAR(20)
);

ALTER TABLE tenants
    ADD COLUMN organization_id BIGINT,
    ADD CONSTRAINT fk_tenants_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id);

CREATE INDEX idx_tenants_organization_id ON tenants(organization_id);

ALTER TABLE invoices
    ADD COLUMN issuer_type VARCHAR(32),
    ADD COLUMN issuer_name VARCHAR(255),
    ADD CONSTRAINT chk_invoices_issuer_type
        CHECK (issuer_type IS NULL OR issuer_type IN ('STORE', 'ORGANIZATION'));

ALTER TABLE non_gst_invoices
    ADD COLUMN issuer_type VARCHAR(32),
    ADD COLUMN issuer_name VARCHAR(255),
    ADD CONSTRAINT chk_non_gst_invoices_issuer_type
        CHECK (issuer_type IS NULL OR issuer_type IN ('STORE', 'ORGANIZATION'));
