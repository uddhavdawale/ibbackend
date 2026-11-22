# HSN-Based GST Backend

## Rate master and catalogue contract

GST rates are stored in the local `hsn_tax_rates` table. Product catalogue responses from `GET /api/products` and `GET /api/billing/products` include the product's canonical `hsn`, its `gstRate`, and `gstRateStatus` (`RESOLVED`, `HSN_MISSING`, `HSN_INVALID`, or `RATE_NOT_FOUND`). An unresolved rate is returned with `gstRate: null`; no price- or category-based fallback is used.

The master is intentionally created empty. No authoritative rate dataset was supplied, and rates must not be inferred from broad HSN chapters. A system administrator can maintain entries using:

- `GET /api/admin/hsn-tax-rates?page=0&size=100`
- `PUT /api/admin/hsn-tax-rates/{hsnCode}` with `{"gstRate": 18}`
- `PUT /api/admin/hsn-tax-rates` with a JSON array of `{"hsnCode":"1234","gstRate":18}` records (up to 5,000 per batch)
- `POST /api/admin/hsn-tax-rates/upload` with a multipart `file` field containing a CSV (up to 5 MB and 5,000 rows)
- `DELETE /api/admin/hsn-tax-rates/{hsnCode}`

The CSV requires exactly one HSN/SAC code column (`hsnCode`, `hsn`, `hsnSac`, `sac`, or equivalent) and exactly one rate column (`gstRate`, `gst`, `rate`, or `taxRate`). HSN/SAC codes must contain 4, 6, or 8 digits. Rates must be between 0 and 100 and may have up to two decimal places. This is a local database lookup (batched for catalogue pages), not an external call per product or cart item. The master must be populated from an approved, maintained GST rate source before enabling GST invoices.

[`sample_hsn_tax_rates_test_only.csv`](./sample_hsn_tax_rates_test_only.csv) is a format smoke-test file only. Its synthetic codes and rates are not valid GST guidance and must not be loaded into production.

## Invoice contract and calculations

`GET /api/shop/me` returns `shop.canCreateGstInvoice` for the authenticated tenant. The frontend should hide GST billing when this is false. GST invoice creation also enforces that the store has a valid GSTIN; an organization issuer cannot bypass this store-level requirement. Non-GST invoice creation remains available for stores without a GSTIN.

GST invoice creation accepts `taxPricingMode` (`exclusive` or `inclusive`) and `placeOfSupplyState` (a supported Indian state name or two-digit GST state code). Each item must include `productId`, `hsn`, `qty`, and the selected unit price; `enteredPrice` is used when present. Existing client tax amounts, HSN rate, totals, names, and SKU are not authoritative.

The server loads products by both product ID and authenticated tenant, verifies the submitted HSN, resolves the rate from the master, and uses the catalogue price. A submitted price that differs from the current product price is rejected. Missing HSN/rates, invalid place-of-supply data, or mismatches produce HTTP 400 validation errors before stock is deducted.

Tax is calculated and rounded to paise per line. Inclusive pricing extracts taxable value from the gross amount; exclusive pricing adds tax to the taxable amount. Intra-state tax is split into CGST and SGST with the second component receiving any paise remainder; inter-state tax is recorded as IGST. The invoice and each item persist the selected tax mode and calculated tax values so subsequent master updates do not change saved invoices.

The selected invoice issuer's state or a valid GSTIN must be configured, and the request must provide the buyer's place-of-supply state. This implementation uses the selected invoice issuer's profile and does not infer a buyer state from a phone number or other client tax fields.

## Safe invoice retries

Both `POST /api/invoices` (GST) and `POST /api/non-gst-invoices` (non-GST) accept an optional `Idempotency-Key` header for backward compatibility. New frontend clients should send a fresh random key per invoice intent and reuse that same key and request body for retries. Keys are scoped to the authenticated tenant; reusing a key with a different invoice type or request returns HTTP 409. A replay returns the original saved invoice response and `Idempotency-Replayed: true`, without deducting stock or recording payment again.

The key claim, invoice write, stock changes, and payment record are transactional. PostgreSQL uniqueness serializes concurrent retries, and failed invoice transactions roll back the key claim so a corrected submission can use a new key. The key is optional only to avoid breaking older clients; calls without it do not receive duplicate protection. Migration V34 adds one small idempotency record per keyed invoice.
