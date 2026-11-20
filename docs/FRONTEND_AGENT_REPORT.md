# Frontend Agent Implementation Report

## Purpose
This document is the authoritative backend contract for the frontend agent. Use it as the source of truth for API integration, tenant scoping, payment truth, and session handling.

The backend is intentionally strict: the server derives the tenant and user context from the authenticated session and JWT claims. The frontend must not override tenant or user identity on protected requests.

---

## 1. Authentication and session rules

### 1.1 Login endpoint
- Method: `POST`
- Path: `/api/auth/login`
- Request body:

```json
{
  "username": "owner@example.com",
  "password": "secret"
}
```

Expected response:

```json
{
  "message": "Login successful",
  "accessToken": "jwt.access.token",
  "refreshToken": "rotating.refresh.token",
  "tokenType": "Bearer",
  "user": {
    "id": 12,
    "username": "Owner",
    "name": "Owner",
    "email": "owner@example.com",
    "role": "STORE_ADMIN",
    "businessName": "Demo Store",
    "tenantId": 7
  }
}
```

Important frontend rules:
- Store `accessToken` and send it as `Authorization: Bearer <token>`
- Treat `refreshToken` as a rotating token; do not reuse it after a refresh flow
- The backend also sets a cookie named `refreshToken` with `HttpOnly` and `Secure`
- If the refresh flow fails, log the user out immediately and redirect to login

### 1.2 Refresh endpoint
- Method: `POST`
- Path: `/api/auth/refresh`
- Request payload (body is optional when cookie is used):

```json
{
  "username": "owner@example.com",
  "refreshToken": "currentRefreshToken"
}
```

Expected response:

```json
{
  "success": true,
  "message": "Token refreshed successfully",
  "accessToken": "new.jwt.access.token",
  "refreshToken": "new.rotating.refresh.token",
  "userName": "Owner",
  "user": {
    "id": 12,
    "username": "Owner",
    "name": "Owner",
    "email": "owner@example.com",
    "role": "STORE_ADMIN",
    "businessName": "Demo Store",
    "tenantId": 7
  }
}
```

Frontend rules:
- If refresh returns `401`, clear local auth state and force re-login
- Do not retry refresh endlessly in a loop
- Do not send stale or custom tenant IDs during refresh

### 1.3 Logout endpoint
- Method: `POST`
- Path: `/api/auth/logout`

Expected response:

```json
{
  "success": true,
  "message": "Logged out successfully"
}
```

- Clear local tokens and cookies
- If the server rejects logout, still clear client-side auth state

### 1.4 Unauthorized behavior
- Any `401` on a protected API must trigger immediate frontend logout
- The backend enforces access by tenant and throws `AccessDeniedException` if tenant information is missing or invalid

---

## 2. Tenant scoping requirements

### Core rule
The backend authoritatively resolves the current tenant from the authenticated JWT and request context. It does not trust client-side `tenantId` values.

Frontend must follow this rule:
- Never send `tenantId` from the client to override business data
- Never attempt cross-tenant requests
- Always rely on the authenticated session and backend-owned tenant context

### Security implication
This prevents:
- data leakage across users
- SKU collisions between distinct shops
- invoice/product access across tenants
- SQL injection or unsafe query construction through manipulated tenant values

The backend repositories use parameterized queries with tenant filters such as `tenant.id = :tenantId` and reject mismatched tenant data.

---

## 3. Dashboard contract

### 3.1 Dashboard metrics
- Method: `GET`
- Path: `/api/dashboard/metrics`
- User must be authenticated

Expected response:

```json
{
  "salesToday": 1250,
  "pendingPayments": 750,
  "lowStock": 8,
  "ordersToday": 12
}
```

Important notes:
- Keys are intentionally named exactly as shown above
- Values are numeric and server-derived
- `pendingPayments` is the tenant-scoped outstanding amount, not the frontend status field

### 3.2 Dashboard summary alias
- Method: `GET`
- Path: `/api/dashboard/summary`
- Same response body as `/api/dashboard/metrics`

---

## 4. Shop profile contract

### New dedicated endpoint
This is the required endpoint for UI store/business metadata.

- Method: `GET`
- Path: `/api/shop/me`
- Also supported: `/api/shop` and `/api/shop/`

Expected response:

```json
{
  "success": true,
  "shop": {
    "id": 12,
    "tenantId": 7,
    "tenantCode": "DEMO7",
    "tenantName": "Demo Store",
    "businessName": "Demo Store",
    "shopName": "Demo Store",
    "displayName": "Demo Store",
    "ownerName": "Owner",
    "email": "owner@example.com",
    "phone": "9876543210",
    "gstin": "22AAAAA0000A1Z5",
    "pan": "AAAAA0000A",
    "role": "STORE_ADMIN",
    "address": "Main Road",
    "shopAddress": "Main Road",
    "city": "Pune",
    "pincode": "411001"
  }
}
```

Frontend guidance:
- Use this endpoint for store branding, invoice header, PDF company details, and app shop metadata
- Do not mix shop metadata into general user profile payloads
- This endpoint is tenant-aware and must reflect the signed-in business only

---

## 5. Invoice contract

### 5.1 Invoice list
- Method: `GET`
- Path: `/api/invoices`
- Query params: `page`, `size`, `search`, `status`

Example response:

```json
{
  "content": [
    {
      "id": 101,
      "invoiceNo": "INV-2026/0001",
      "date": "2026-10-24",
      "customerName": "Walk-in Customer",
      "customerPhone": "0000000000",
      "customerEmail": "customer@example.com",
      "paymentMethod": "UPI",
      "subtotal": 95,
      "gstAmount": 10,
      "grandTotal": 105,
      "totalAmount": 105,
      "paidAmount": 105,
      "outstandingAmount": 0,
      "paymentStatus": "PAID",
      "status": "Paid",
      "tenantId": 7,
      "userId": 12,
      "items": []
    }
  ],
  "pageable": {},
  "totalPages": 1,
  "totalElements": 1,
  "last": true,
  "first": true,
  "number": 0,
  "size": 10
}
```

### 5.2 Critical payment rule
The frontend must not trust a client-provided `status` or `paymentStatus` as the source of truth for invoice payment state.

The backend calculates the canonical state using real amounts:
- if `paidAmount >= grandTotal` => `PAID`
- if `0 < paidAmount < grandTotal` => `PARTIAL`
- otherwise => `PENDING`

Then the backend maps that to invoice status and stores the result on the server.

Frontend behavior:
- Treat `status` and `paymentStatus` from the backend as authoritative
- Do not override these values in the UI based on stale frontend state
- Do not show a sale as “pending” if the backend already marked it as paid

### 5.3 Invoice creation contract
- Method: `POST`
- Path: `/api/invoices`

Request example:

```json
{
  "customerName": "Walk-in Customer",
  "customerPhone": "0000000000",
  "paymentMethod": "UPI",
  "status": "PAID",
  "paidAmount": 50,
  "items": [
    {
      "productId": 10,
      "name": "Notebook",
      "sku": "NOTEBOOK-007",
      "hsn": "9983",
      "gstRate": 5,
      "price": 100,
      "qty": 1
    }
  ]
}
```

Server response example:
- The backend ignores the client status as a source of truth
- It recalculates `paidAmount` and `paymentStatus` based on the real grand total and payment amount

Example saved values:

```json
{
  "paidAmount": 105,
  "paymentStatus": "PAID",
  "status": "Paid"
}
```

This behavior is required to prevent pending status being stored incorrectly when the user has already paid.

---

## 6. Product and inventory rules

### Product listing
- Method: `GET`
- Path: `/api/products`
- Returns tenant-scoped product data only

Expected page response:

```json
{
  "content": [
    {
      "id": 1,
      "name": "Product A",
      "sku": "SKU-01",
      "quantity": 10,
      "minStock": 20,
      "price": 299,
      "category": "Electronics"
    }
  ],
  "totalPages": 5,
  "totalElements": 42,
  "number": 0,
  "size": 10
}
```

### Important rules
- Product duplicate checks are tenant-scoped
- SKU uniqueness is enforced within the current tenant only
- Data must never be shared across tenants
- Use tenant context from backend, not request payloads

---

## 7. Security and hardening requirements

### Must be enforced by frontend
- Use secure tokens only; do not store raw refresh tokens in localStorage if avoidable
- Treat all response data as tenant-scoped and user-scoped
- If a request fails with `401`, log the user out immediately
- Prevent user/tenant ID tampering via request payloads

### Backend security guarantees
The application enforces: 
- JWT-based authentication
- refresh token rotation and revocation
- tenant-based repository filtering
- server-side validation of invoice payment state
- SQL parameterization and no unsafe string concatenation for tenant or product filters

### SQL injection protection
The backend is protected by parameterized JPA queries and tenant-specific repository methods. The frontend should never rely on direct raw SQL or custom query strings. If the frontend sends untrusted identifiers, the backend rejects them and the API fails safely.

---

## 8. MFA and login flow notes

The backend supports optional mail-based MFA:
- login may require MFA before issuing access tokens
- `POST /api/mfa/send-otp` and `POST /api/mfa/verify-otp` are the relevant endpoints

Frontend guidance:
- If login response indicates MFA is required, move to the OTP step instead of continuing the normal dashboard flow
- Keep MFA steps separate from normal login state
- Do not disable or bypass the MFA challenge when the backend has it enabled

---

## 9. Required frontend expectations

The frontend agent should:
1. Use `/api/auth/login` and `/api/auth/refresh` for auth flows
2. Use `/api/shop/me` for business/store metadata
3. Use `/api/dashboard/metrics` for dashboard KPI values
4. Use `/api/invoices` for invoice lists and invoice truth
5. Use backend invoice `paidAmount`, `paymentStatus`, and `status` values as the single source of truth
6. Never trust client-side `tenantId`, `userId`, or invoice status overrides
7. Treat the server as the final authority over identity, tenant scope, and payment state

---

## 10. Acceptance checklist

- [x] login returns access and refresh token
- [x] refresh token rotation is implemented and enforced
- [x] tenant is assigned uniquely per user/store
- [x] user data is scoped to the current tenant
- [x] /api/shop/me returns dedicated shop metadata
- [x] invoice payment status is derived from backend-calculated real amounts
- [x] frontend is expected to log out on any 401 response
- [x] product and invoice access are tenant-isolated

---

## 11. Implementation note
Use this file as the contract for frontend-screen implementation. If a frontend change depends on a field that is not listed here, it should be validated with the backend before merging. The backend is authoritative for all tenant, identity, payment, and access-control logic.
