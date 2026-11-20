# Frontend Inventory Security Implementation Report

Date: 2026-10-29
Project: ibbackend
Audience: Frontend team

## 1. Purpose
This report explains what changed in backend inventory behavior and what frontend must implement or verify for safe integration.

## 2. Frontend-Relevant Backend Changes

### 2.1 Product write payload is now explicit
Product create/update endpoints are DTO-driven:
- `POST /api/products`
- `PUT /api/products/{id}`

Expected payload fields:
- `name`
- `sku`
- `hsnSac`
- `quantity`
- `minStock`
- `price`
- `mrp`
- `setSize`
- `poNumber`
- `salesCount`
- `category`
- `expiryDate`

Frontend action:
- Ensure forms submit only allowed fields above.
- Remove any legacy entity-level extra fields from request payload.

### 2.2 Sell order cancellation APIs available
GST:
- `POST /api/invoices/{id}/cancel`

Non-GST:
- `POST /api/non-gst-invoices/{id}/cancel`

Response behavior:
- Idempotent for already cancelled orders (`success: true` with status message)
- Returns `invoiceId`, `invoiceNo`, `status`, and restocked count where applicable

Frontend action:
- Add cancel button/state for eligible sell orders.
- Handle already-cancelled response as successful UX state.

### 2.3 Tenant-scoped uniqueness for invoice numbers
Invoice numbers are unique per tenant (not globally).

Frontend action:
- Do not assume globally unique invoice numbers across all tenants.
- Display invoice numbers with tenant context where needed in admin multi-tenant screens.

### 2.4 Stock now updates through secured backend flow
Backend enforces stock checks and logs movement history for:
- invoice creation
- invoice cancellation
- product stock adjustments
- inventory upload

Frontend action:
- Always refresh product/invoice lists after create/cancel/update actions.
- Do not perform optimistic stock math as source of truth; reconcile from API response/list refresh.

## 3. Endpoint Contract Summary for Frontend

### Product Management
- `GET /api/products`
- `POST /api/products`
- `PUT /api/products/{id}`
- `DELETE /api/products/{id}`

Notes:
- Duplicate SKU/HSN-SAC within same tenant can return conflict.
- Defaults may be applied by backend (category, set size, etc.).

### GST Billing
- `POST /api/invoices`
- `GET /api/invoices`
- `GET /api/invoices/{id}`
- `POST /api/invoices/{id}/cancel`

### Non-GST Billing
- `POST /api/non-gst-invoices`
- `GET /api/non-gst-invoices`
- `POST /api/non-gst-invoices/{id}/cancel`

### Inventory Upload
- `POST /api/inventory/upload`

## 4. UI/UX Recommendations

### 4.1 Cancellation UX
- Show `Cancel Sell Order` only for cancellable states.
- After cancellation success, refresh invoice details and product stock views.
- Display backend message directly for clarity.

### 4.2 Validation UX
- Prevent negative quantity input in client forms.
- Still rely on backend validation as final authority.

### 4.3 Error handling
Handle these classes cleanly:
- 400: insufficient stock / invalid request
- 403: tenant access violation or approval gate (future)
- 404: invoice not found
- 409: uniqueness conflict

### 4.4 Audit-facing context
When possible, include clear user intent in UI labels and action confirmations (e.g., adjustment reason prompt). This improves operational traceability even though backend already logs movement metadata.

## 5. QA Scenarios for Frontend

1. Create product with initial quantity > 0 and verify stock appears correctly.
2. Update product quantity up/down and verify stock list refresh.
3. Create GST sell order and verify stock decreases.
4. Cancel GST sell order and verify stock restores.
5. Create Non-GST sell order and verify stock decreases.
6. Cancel Non-GST sell order and verify stock restores.
7. Retry cancellation on already-cancelled invoice and verify idempotent success UX.
8. Upload inventory rows that update existing SKU and verify stock reflects uploaded quantity.

## 6. Current Limitation to Keep in Mind
Approval workflow for high-risk inventory actions is not fully exposed yet (next phase). Frontend should be built so a future `PENDING_APPROVAL` state can be added without major redesign.
