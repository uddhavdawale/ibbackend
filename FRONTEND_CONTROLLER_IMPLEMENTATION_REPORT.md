# Frontend Controller Implementation Report

## 1. Purpose
This report documents the actual backend contract that the frontend should consume for authentication, tenant-scoped data, and payment state. It is intended to prevent stale client-side assumptions and to ensure the UI always reflects the backend source of truth.

---

## 2. Authentication and tenant model

### 2.1 Login endpoints
The backend exposes the following login routes:

- POST /api/login
- POST /login
- POST /api/auth/login
- POST /api/auth/signup
- POST /api/auth/refresh
- POST /api/auth/logout

Primary frontend target:
- Prefer /api/login for business login flow.
- Support /api/auth/login if the app is using the auth controller during rollout.

### 2.2 JWT contract
The JWT contains:
- sub = user email
- role
- tokenType = access or refresh
- tenantId = current tenant id for the authenticated user

Authorization header:
- Authorization: Bearer <token>

Refresh token:
- Cookie name: refreshToken
- HttpOnly, Secure, sameSite=Lax

### 2.3 Required tenant behavior
Each user must have a valid tenant assignment.

Important rule:
- The frontend must never send a tenant id manually to fetch tenant-owned data.
- The backend must derive the tenant from the JWT and enforce it server-side.

This protects the app from cross-tenant data leaks and stale UI assumptions.

---

## 3. User profile contract

### 3.1 Endpoint
GET /api/users/me

### 3.2 Example response
```json
{
  "success": true,
  "user": {
    "id": 1,
    "email": "user@example.com",
    "businessName": "My Business",
    "ownerName": "Owner Name",
    "phone": "9876543210",
    "role": "STORE_ADMIN",
    "gstin": "29ABCDE1234F1Z5",
    "pan": "ABCDE1234F",
    "address": "Main Street",
    "city": "Pune",
    "pincode": "411001",
    "tenantId": 7
  }
}
```

### 3.3 Frontend use
- Use this endpoint for authenticated profile hydration.
- Use current tenantId only for display or local session state.
- Do not trust client-side tenant filters as the only source of access control.

---

## 4. Dashboard contract

### 4.1 Endpoint
GET /api/dashboard/metrics

### 4.2 Example response
```json
{
  "salesToday": 0,
  "pendingPayments": 0,
  "lowStock": 0,
  "ordersToday": 0
}
```

### 4.3 Endpoint
GET /api/dashboard/stats

### 4.4 Example response
```json
{
  "totalProducts": 0,
  "totalOrders": 0,
  "lowStockItems": 0,
  "totalRevenue": 0,
  "salesToday": 0,
  "pendingAmount": 0,
  "pendingCount": 0,
  "soonExpiring": 0,
  "soonExpiryCount": 0,
  "products": []
}
```

### 4.5 Frontend use
- Dashboard widgets must read from backend values only.
- No client-side cross-user or cross-tenant filtering should be applied.

---

## 5. Product contract

### 5.1 Endpoint
GET /api/products

### 5.2 Example response
```json
{
  "content": [
    {
      "id": 1,
      "name": "Rice",
      "sku": "RICE-001",
      "quantity": 20,
      "minStock": 5,
      "price": 250,
      "category": "Groceries",
      "createdAt": "2026-10-24T12:00:00",
      "tenantId": 7
    }
  ],
  "pageable": {},
  "totalElements": 1
}
```

### 5.3 Mutations
- POST /api/products
- PUT /api/products/{id}
- DELETE /api/products/{id}

### 5.4 Frontend use
- Fetch and mutate only within the current tenant.
- Do not filter by arbitrary user id or raw tenant id from the client.
- SKU uniqueness is now tenant-scoped: the same SKU may exist in different tenants.
- Handle duplicate SKU in same tenant as conflict:
  - POST /api/products returns 409 when SKU already exists for that tenant.
  - PUT /api/products/{id} returns 409 when changing SKU to one already used by another product in that tenant.

---

## 5A. Inventory bulk upload contract

### 5A.1 Endpoint
POST /api/inventory/upload (multipart/form-data)

### 5A.2 Request fields
- file: CSV/XLS/XLSX inventory file
- source: optional client source label
- records: optional JSON payload mode (if file is not sent)
- tenantId: optional, but must match authenticated tenant when provided
- userId: optional, but must match authenticated user when provided

Supported bulk upload template headers (new flow):
- Item
- HSN/SAC
- MRP/ Item
- QTY
- Set
- Price/QTY
- PO Number
- Min Stock
- Category

Backend mapping for this template:
- Item -> product
- SKU -> uses provided SKU or defaults to HSN/SAC when SKU is absent
- HSN/SAC -> required; persisted as hsnSac
- QTY -> qty
- Set -> set (defaults to 1 when empty)
- Min Stock -> minStock (defaults to 0 when empty)
- Price/QTY -> pricePerQty and selling price (primary)
- MRP/ Item -> mrp (required) and selling price fallback when Price/QTY is empty
- PO Number -> poNumber
- Category -> category (defaults to General when empty)

### 5A.3 Tenant behavior
- Backend resolves tenant from JWT/TenantContext.
- Uploaded rows are applied only to the authenticated tenant.
- Same SKU in same tenant is treated as update (not validation failure).
- Same SKU across different tenants is allowed and treated independently.

### 5A.4 Response shape
```json
{
  "success": true,
  "message": "Inventory uploaded successfully",
  "created": 2,
  "updated": 3,
  "skipped": 1,
  "records": []
}
```

### 5A.5 Frontend rule
- Do not rely on manually switching tenantId in upload requests.
- Show server counts (created/updated/skipped) as final truth.
- Treat upload errors as validation/security failures and display backend message directly.

---

## 6. Invoice and payment contract

### 6.1 Endpoint
GET /api/invoices

### 6.2 Example response
```json
{
  "content": [
    {
      "id": 101,
      "totalAmount": 1000.00,
      "paidAmount": 350.00,
      "outstandingAmount": 650.00,
      "paymentStatus": "PARTIAL",
      "status": "Pending",
      "customerName": "Ravi Sharma",
      "createdAt": "2026-10-24T00:00:00"
    }
  ]
}
```

### 6.3 Single invoice response
```json
{
  "id": 101,
  "invoiceNo": "INV-2026/0001",
  "customerName": "Ravi Sharma",
  "customerPhone": "9876543210",
  "date": "2026-10-24",
  "paymentMethod": "UPI",
  "subtotal": 850.00,
  "gstAmount": 150.00,
  "grandTotal": 1000.00,
  "paidAmount": 350.00,
  "outstandingAmount": 650.00,
  "paymentStatus": "PARTIAL",
  "status": "Pending"
}
```

### 6.4 Canonical payment state
The backend is the source of truth for payment state:
- paymentStatus: PENDING | PARTIAL | PAID | CANCELLED
- totalAmount: grandTotal
- paidAmount: actual amount received
- outstandingAmount: totalAmount - paidAmount

### 6.5 Frontend rule
The UI must render payment state from backend fields only and must not infer status from stale status-only values or old cached flags.

---

## 7. Security and frontend handling

### 7.1 Required behaviors
- Always send Authorization: Bearer <token>
- Keep refreshToken in secure cookie storage
- Treat 401 and 403 as invalid session or cross-tenant access
- On auth failure, clear local session immediately
- Never trust client-side filters as access control

### 7.2 Frontend session flow
1. User logs in via /api/login
2. If MFA is required, go to OTP step
3. Verify MFA and store tokens only after successful verification
4. Fetch /api/users/me
5. Fetch tenant-scoped dashboard and list data
6. Render UI from backend values only

---

## 8. Final frontend guidance
The backend is ready for UI integration with the following contracts:
- login and MFA flow
- signup flow
- JWT session flow
- profile fetch
- dashboard metrics and stats
- products list and CRUD
- invoice list and detail
- payment-state display from backend truth

The most important rule is:
- backend decides tenant and payment truth
- frontend displays backend data, without guessing from stale fields

---

## 9. Short implementation checklist for controller/frontend team
- Update login handling to use /api/login and accept MFA challenge flow
- Store accessToken + refreshToken after successful OTP verification
- Hydrate user from /api/users/me
- Load dashboard stats from /api/dashboard/metrics and /api/dashboard/stats
- Load products from /api/products and invoices from /api/invoices
- Handle 409 conflicts from product create/update for same-tenant duplicate SKU
- Use /api/inventory/upload for tenant-scoped bulk inventory create/update
- Render payment using paymentStatus, paidAmount, totalAmount, outstandingAmount
- Never rely on stale or locally computed payment status
