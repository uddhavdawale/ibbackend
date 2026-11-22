# Retailer organization and store invoices

Stores remain separate data tenants. Assigning a store to an organization only adds a parent relationship; it does not combine store inventory, customers, users, or invoice access.

## System-admin organization management

All endpoints below require an authenticated system-admin account.

- `POST /api/admin/organizations` creates an organization. Body: `{"name":"Retail Group","address":"Main Road","city":"Pune","state":"Maharashtra","pincode":"411001","gstin":"27ABCDE1234F1Z5","phone":"0201234567","email":"billing@example.com"}`. Only `name` is required.
- `GET /api/admin/organizations?page=0&size=20` lists organizations.
- `GET /api/admin/stores?page=0&size=20&search=store` lists stores and their current organization assignment.
- `PUT /api/admin/organizations/{organizationId}/stores/{tenantId}` assigns or reassigns one store.
- `DELETE /api/admin/organizations/{organizationId}/stores/{tenantId}` removes that store's assignment.

Store assignment is optional and one store can belong to at most one organization. Existing stores are not automatically moved into an organization.

## Invoice issuer

GST and non-GST invoice creation accepts an optional `issuerType`:

- `STORE` uses the store's name. This is the default when the field is omitted, preserving existing clients.
- `ORGANIZATION` uses the assigned organization's name. An unassigned store cannot use this option.

`GET /api/shop/me` returns the authenticated store's optional `organization` summary (`id`, name, GSTIN, contact, and registered address). The response is derived from the authenticated user's store relationship.

New invoices persist `issuerType`, `issuerName`, `issuerGstin`, `issuerAddress`, `issuerPhone`, and `issuerEmail` as a snapshot. GST invoices issued as an organization require a valid organization GSTIN and registered street address. Later organization or store profile changes do not alter previously issued invoices. The store tenant continues to scope invoice reads and all retailer data.

Store GSTIN, phone, and billing email are stored on the store tenant profile. Existing store-admin profile values are backfilled during migration; `/api/shop/profile` accepts optional `gstin`, `phone`, and `storeEmail` fields to update them without changing the account login email.
