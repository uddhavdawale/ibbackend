# Frontend Implementation Report: HSN-Based GST Billing

**Audience:** Billing frontend agent
**Backend status:** The catalogue and invoice APIs now support backend-resolved HSN tax rates and server-calculated invoice taxes.

## 1. Show invoice types based on store GST registration

Load the authenticated shop profile from `GET /api/shop/me`. The `shop` object now includes `canCreateGstInvoice`.

- If `canCreateGstInvoice` is `true`, show the GST invoice option alongside the existing non-GST option.
- If it is `false`, hide/disable the GST invoice option and keep non-GST billing available.

The value is based on the authenticated tenant's valid GSTIN (with the existing store-owner GSTIN fallback). An organization GSTIN alone does not enable GST billing for an unregistered store. This is only a UI hint: the backend also rejects GST invoice requests without a valid store GSTIN, so hiding the option is not a security control by itself.

## 2. Load GST data with the product catalogue

Use the existing tenant-scoped product-list API; do not make a GST request when a cart item is added or its quantity changes:

- `GET /api/products`
- `GET /api/billing/products`

Both responses include a canonical HSN/SAC and the resolved rate. The response is paged; use its `content` array.

`GET /api/products` returns the fields `id`, `hsn`, `gstRate`, and `gstRateStatus`. `GET /api/billing/products` returns product entities, including `id`, `hsnSac`, `gstRate`, and `gstRateStatus`. The frontend can normalize the HSN from `hsn`, `hsnCode`, `hsnSac`, or `hsn/sac` if needed.

Example:

```json
{
  "id": 42,
  "name": "Example product",
  "sku": "ITEM-42",
  "hsn": "1234",
  "price": 100.00,
  "gstRate": 18.00,
  "gstRateStatus": "RESOLVED"
}
```

`gstRate` is a JSON number and may contain decimal places. Interpret `gstRateStatus` as follows:

| Status | Frontend behavior |
| --- | --- |
| `RESOLVED` | GST billing can use the returned rate. |
| `HSN_MISSING` | Ask the retailer/admin to add an HSN/SAC to the product. |
| `HSN_INVALID` | Ask the retailer/admin to correct the product code. |
| `RATE_NOT_FOUND` | The code is valid but has no rate in the backend master; do not guess a rate. |

Block GST invoice generation if any line has a missing/invalid HSN, unresolved status, or invalid rate. Non-GST invoice behavior is unaffected.

## 3. Keep the cart preview consistent with the server

Use the product catalogue's `price` as the billable unit price. The backend currently rejects a submitted entered price that differs from the product's current catalogue price; do not substitute `mrp` or send a custom price unless backend pricing rules are added.

Allow the retailer to choose `exclusive` or `inclusive` pricing, and keep the selection at invoice and item level. The backend accepts only those values (case-insensitive); it defaults a missing mode to `exclusive`, but the frontend should send the selected mode explicitly.

Calculate the preview per line and round to two decimals:

- **Exclusive:** entered unit price is the taxable base; calculate GST on the line taxable value, then add GST to get the line total.
- **Inclusive:** entered unit price is the gross amount; `taxable = gross / (1 + rate / 100)`, rounded to paise; line tax is `gross - taxable`.
- **Intra-state:** split GST into CGST and SGST. Round CGST to paise and set SGST to the remainder so both components equal the line tax.
- **Inter-state:** show IGST instead of CGST/SGST.

For example, at 18% and quantity one:

| Mode | Taxable | CGST | SGST | Total |
| --- | ---: | ---: | ---: | ---: |
| Inclusive, price ₹100 | ₹84.75 | ₹7.63 | ₹7.62 | ₹100.00 |
| Exclusive, price ₹100 | ₹100.00 | ₹9.00 | ₹9.00 | ₹118.00 |

Use the selected invoice issuer's location and the buyer's place of supply to preview the tax component. The backend requires `placeOfSupplyState` to be a supported Indian state name or two-digit GST state code, and requires the issuer's state or a valid GSTIN to be configured. Include the buyer state in the request; do not infer it from a phone number.

## 4. Submit the invoice once

Submit the invoice and all items in one authenticated request:

`POST /api/invoices`
(`/api/orders` and `/api/invoice` are also supported.)

Send a fresh random `Idempotency-Key` header for each new invoice intent and reuse it unchanged when retrying that same request after a timeout or ambiguous result. Do not generate a new key for an automatic retry. The backend supports keyed retries for GST and non-GST invoices, returns the saved result with `Idempotency-Replayed: true`, and responds with HTTP 409 if the same key is reused for different invoice data. The header remains optional for older clients, but requests without it cannot be deduplicated across tabs or clients.

Example shape:

```json
{
  "customerName": "Walk-in customer",
  "customerPhone": "0000000000",
  "paymentMethod": "UPI",
  "status": "PENDING",
  "taxPricingMode": "inclusive",
  "placeOfSupplyState": "27",
  "subtotal": 84.75,
  "gstAmount": 15.25,
  "grandTotal": 100.00,
  "totalAmount": 100.00,
  "items": [
    {
      "productId": 42,
      "hsn": "1234",
      "gstRate": 18,
      "qty": 1,
      "price": 84.75,
      "enteredPrice": 100.00,
      "taxPricingMode": "inclusive",
      "taxableAmount": 84.75,
      "taxAmount": 15.25,
      "cgst": 7.63,
      "sgst": 7.62,
      "igst": 0.00,
      "cgstAmount": 7.63,
      "sgstAmount": 7.62
    }
  ]
}
```

The client tax amounts, totals, submitted rate, item name, and SKU are untrusted. They are useful for preview/compatibility only; the server loads the product in the authenticated tenant, checks the submitted HSN, gets the rate from its master, and recalculates the invoice. The backend currently uses the catalogue price as authoritative and validates `enteredPrice` (or `price` if `enteredPrice` is absent) against it.

The `placeOfSupplyStateCode` request alias is accepted, but prefer `placeOfSupplyState`.

## 5. Use the server response and show validation errors

On success, replace the local preview totals with the returned invoice and item totals. The response contains the resolved `gstRate`, canonical product HSN, `taxPricingMode`, `enteredPrice`, `taxableAmount`, `taxAmount`, tax components, and stored totals. In inclusive mode, returned `price` is the derived taxable unit price; `enteredPrice` retains the gross catalogue price.

For validation failures, the backend returns HTTP 400 with this shape:

```json
{
  "success": false,
  "message": "Notebook (product 42) has no applicable GST rate in the HSN/SAC tax master"
}
```

Show the returned `message` and keep the cart available for correction. Product-specific validation can also identify tenant/product mismatches, an HSN mismatch, invalid/missing rates, invalid place of supply, missing issuer state, or an entered-price mismatch.

## 6. Deployment dependency

The database-backed HSN master is intentionally empty until an approved GST rate dataset is loaded. A system administrator can upload a CSV to `POST /api/admin/hsn-tax-rates/upload` using multipart form field `file`. Use one HSN/SAC code column (`hsnCode`, `hsn`, `hsnSac`, or `sac`) and one rate column (`gstRate`, `gst`, `rate`, or `taxRate`); the upload limit is 5 MB and 5,000 rows. The endpoint returns `success` and `importedCount`. The system admin UI should send the authenticated admin token and submit the selected file as `FormData` without manually setting the multipart `Content-Type`.

The frontend should not maintain or infer tax rates. Until relevant HSN rows are populated, catalogue products will report `RATE_NOT_FOUND` and GST invoice submission will be rejected for those products.

## Frontend acceptance checklist

- [ ] Read `shop.canCreateGstInvoice` from `GET /api/shop/me`; hide GST billing when it is false.
- [ ] Read HSN, numeric `gstRate`, and `gstRateStatus` from the existing product-list response.
- [ ] Do not call a rate endpoint during cart interactions.
- [ ] Prevent GST submission when any item is unresolved; explain the product-specific status.
- [ ] Include invoice and item `taxPricingMode`, plus `placeOfSupplyState`.
- [ ] Send each item's tenant product ID, canonical HSN, quantity, and catalogue price in `enteredPrice`; send derived taxable unit `price` for inclusive compatibility.
- [ ] Treat client tax values as preview only and use server-returned totals after success.
- [ ] Display the backend's HTTP 400 `message` without discarding the cart.
- [ ] Send and reuse one `Idempotency-Key` per invoice intent, including retries.
