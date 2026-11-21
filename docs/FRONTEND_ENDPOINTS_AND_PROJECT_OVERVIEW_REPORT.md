# Inventory & Billing Backend – Frontend API and Project Overview Report

Date: 2026-11-08
Project: IB Backend (Inventory & Billing)
Audience: Frontend team, product team, admin panel developers, QA

## 1) Executive Summary

This backend is a multi-tenant retail inventory and billing platform built with Java 17 and Spring Boot 3.5.3. It supports product management, inventory uploads, customer records, GST and non-GST sales, returns, exchanges, dashboard analytics, MFA-based login, JWT-based session management, audit logging, and store-admin user management.

For frontend delivery, the most important modules are:
- Authentication and session management
- Dashboard analytics
- Product and inventory management
- Billing and invoice workflows
- Customer management
- Store admin / retail user management
- Audit and security reporting

The project already exposes most of the APIs required to build an admin console for managing retail users and tenant-level operations.

---

## 2) Project Overview

### 2.1 Product purpose
The application is designed for small to medium retail businesses that need:
- product catalog management
- stock tracking and inventory upload
- sales and invoice generation
- returns and exchanges
- customer profile tracking
- tenant-aware dashboards
- user administration inside each store/tenant

### 2.2 Core technology stack
- Java 17
- Spring Boot 3.5.3
- Spring Web / REST API
- Spring Security
- Spring Data JPA
- PostgreSQL
- JWT authentication and refresh token flow
- MFA support via email verification
- Apache POI and OpenCSV for inventory import
- Audit log persistence for security/business events

### 2.3 Multi-tenancy and role model
The backend is tenant-aware and current-user-aware. Most APIs depend on the resolved current tenant context rather than a frontend-supplied tenant value.

Important roles and admin patterns:
- `store_admin`, `owner`, `admin`, `shop_owner`, `retailer`, `super_admin`, `org_admin` are treated as management-capable roles.
- `disabled_user` is treated as blocked/disabled.
- User management APIs are restricted to store-admin level roles through `UserRolePolicy.canManageUsers(...)`.

### 2.4 Security model
- JWT access tokens for API authorization
- Refresh token rotation and cookie storage
- MFA challenge before final login completion when enabled
- Tenant-scoped access enforcement
- Request ID + audit logging for security and operational traceability

---

## 3) Frontend Delivery Scope and Implementation Guidance

The frontend should be organized around the following areas:

### 3.1 Authentication and account flow
- Login
- MFA challenge and verification
- Refresh token flow
- Logout
- Session recovery

### 3.2 Dashboard
- sales summary cards
- pending payment status
- low-stock warnings
- order counts
- top sold products

### 3.3 Product and inventory management
- product listing and filtering
- add / edit / delete product
- stock adjustment and inventory upload
- expiry and low-stock data

### 3.4 Sales and billing
- create GST invoices
- list invoices and search/filter by status
- invoice detail view
- cancel sell order
- create non-GST invoices
- returns and exchanges

### 3.5 Customer management
- customer profile creation/update
- customer search by phone/name/query
- customer detail lookup and billing context

### 3.6 Admin panel for retail users
The project already contains the backend foundation for a retail-user admin console. This is the most relevant frontend feature to implement next.

Recommended admin capabilities:
- list tenant users
- search users by name/email/username
- rename user identity
- enable/disable status
- soft delete user
- resend password assistance / OTP email trigger
- display role and tenant metadata
- restrict UI actions based on current user role

---

## 4) API Endpoint Catalog

## 4.1 Authentication APIs

### POST /api/auth/login
Purpose: Authenticate a user and return access token + refresh token.
Notes:
- Current backend accepts login payloads with either plain credentials or encrypted-login envelope support.
- Response includes `accessToken`, `refreshToken`, `tokenType`, `user`.
- Uses cookie-based refresh token handling as well.

### POST /api/auth/refresh
Purpose: Rotate access token and refresh token.
Notes:
- Accepts body token or cookie-based token.
- Returns refreshed access token and user metadata.

### POST /api/auth/logout
Purpose: Clear refresh token and revoke all active tokens for the user.

### POST /api/auth/signup
Purpose: Create a user account under tenant context.
Notes:
- Role assignment and signup audit are supported.

### POST /api/login
Purpose: Legacy or compatibility login route.
Notes:
- Same business purpose as /api/auth/login.

### POST /api/mfa/send
Purpose: Trigger email MFA challenge.

### POST /api/mfa/verify
Purpose: Verify email OTP and finalize successful login.

---

## 4.2 User and Shop Profile APIs

### GET /api/users
Purpose: List users for the authenticated admin's tenant.
Query params:
- `page` (default 0)
- `size` (default 20)
- `search` (optional)
- `includeDisabled` (default false)

### GET /api/users/me
Purpose: Get current authenticated user profile.
Response includes:
- id
- email
- businessName
- ownerName
- phone
- role
- gstin
- pan
- address
- city
- pincode
- tenantId

### GET /api/users/existing
Purpose: Compatibility alias for the tenant user list at `/api/users`.
Query params:
- `page` (default 0)
- `size` (default 20)
- `search` (optional)
- `includeDisabled` (default false)

Response includes:
- `success`
- `items`
- `page`
- `size`
- `totalItems`
- `totalPages`
- `hasMore`
- `tenantId`

### PATCH /api/users/{userId}/rename
Purpose: Rename username and/or owner name.
Notes:
- Validates username format.
- Prevents duplicate username collisions.

### PUT /api/users/{userId}
Purpose: Backward-compatible update route for user edit operations.

### POST /api/users/{userId}/disable
Purpose: Disable a retail user.

### PATCH /api/users/{userId}/status
Purpose: Toggle `disabled` flag in a stateful payload.
Request body example:
```json
{
  "disabled": true
}
```
Setting `disabled` to `true` changes an approved account to `DISABLED`; setting it to
`false` restores a disabled account to `APPROVED`. Other onboarding or blocked states
cannot be enabled through this toggle and return `409 Conflict`.

### DELETE /api/users/{userId}
Purpose: Soft delete user and revoke sessions.
Notes:
- Marked as deleted and disabled.
- Cannot delete your own account.

### POST /api/users/{userId}/resend-password
Purpose: Trigger password assistance flow / OTP resend to user email.
Notes:
- Enforces resend rate limits.
- Revokes current tokens after resend.

### GET /api/shop
### GET /api/shop/
### GET /api/shop/me
Purpose: Get current shop/tenant context.
Returns:
- tenantId
- tenantCode
- tenantName
- businessName
- shopName
- displayName
- ownerName
- email
- phone
- gstin
- pan
- address
- role

---

## 4.3 Dashboard and Analytics APIs

### GET /api/dashboard
### GET /api/dashboard/
### GET /api/dashboard/summary
Purpose: Dashboard summary endpoint.
Notes:
- Returns the same metrics as the dashboard metrics endpoint.

### GET /api/dashboard/metrics
Purpose: Main dashboard metric summary.
Returns:
- `salesToday`
- `pendingPayments`
- `lowStock`
- `ordersToday`
- `topSoldProducts`
- tenant/context details such as `tenantId`, `userId`, `role`
Notes:
- Sales, order counts, revenue, and pending-payment totals include both GST and non-GST invoices for the authenticated tenant.

### GET /api/dashboard/stats
Purpose: Extended dashboard statistics.
Returns:
- total products
- total orders
- low stock items
- total revenue
- sales today
- pending amount
- pending count
- top sold products
- soon-expiring count

---

## 4.4 Product and Inventory APIs

### GET /api/products
Purpose: List paginated product catalog for tenant.
Params:
- `page`
- `size`
- `search`
- `sortBy`
- `sortDir`

### POST /api/products
Purpose: Add product.
Notes:
- Enforces tenant scope.
- Creates stock movement for initial stock if positive quantity.

### PUT /api/products/{id}
Purpose: Update product.
Notes:
- Stock delta is tracked as inventory movement.

### DELETE /api/products/{id}
Purpose: Delete product and perform stock write-off movement if applicable.

### POST /api/inventory/upload
Purpose: Upload product inventory data via multipart file.
Params:
- `file`
- `tenantId` (optional)
- `userId` (optional)
- `source` (optional)
- `records` (optional)

Returns counts for created/updated/skipped records.

---

## 4.5 Billing, GST, Non-GST, and Invoice APIs

### GET /api/billing/products
Purpose: Fetch invoice-ready billing product list for the current tenant.

### GET /api/invoices
### GET /api/orders
### GET /api/invoice
Purpose: List invoices for the current tenant.
Query params:
- `page`
- `size`
- `status`
- `billType`
- `search`

### GET /api/invoices/{id}
### GET /api/orders/{id}
### GET /api/invoice/{id}
Purpose: Get invoice details.

### POST /api/invoices
### POST /api/orders
### POST /api/invoice
Purpose: Create a GST invoice.
Notes:
- Validates authenticated user and tenant context.
- Validates product list and stock availability.
- Deducts stock on sale.

### POST /api/invoices/{id}/cancel
### POST /api/orders/{id}/cancel
### POST /api/invoice/{id}/cancel
Purpose: Cancel a GST sell order and restore stock.

### POST /api/non-gst-invoices
Purpose: Create a Non-GST invoice.

### GET /api/non-gst-invoices
Purpose: List Non-GST invoices with pagination/search.

### POST /api/non-gst-invoices/{id}/cancel
Purpose: Cancel a Non-GST invoice.

### GET /api/gst/summary
Purpose: Fetch GST summary.

### GET /api/gst/filings
Purpose: Fetch GST filing summary list.

---

## 4.6 Customer and Sales Support APIs

### POST /api/customers
### POST /api/customer_profiles
Purpose: Create or update customer profile.

### GET /api/customers
### GET /api/customer_profiles
Purpose: List customers for tenant.

### GET /api/customers/search
### GET /api/customer_profiles/search
Purpose: Search customers.
Query param:
- `query`

### GET /api/customers/phone/{phone}
### GET /api/customer_profiles/phone/{phone}
Purpose: Get customer by phone number.

### GET /api/customers/{id}
### GET /api/customer_profiles/{id}
Purpose: Get customer detail by id.

### PUT /api/customers/{id}
### PATCH /api/customers/{id}
Purpose: Update customer profile.

### POST /api/returns
Purpose: Create a return order.

### GET /api/returns
Purpose: List return orders for the tenant.

### GET /api/returns/{id}
Purpose: Get return order details.

### POST /api/exchanges
Purpose: Create exchange order.

### GET /api/exchanges
Purpose: List exchange orders for the tenant.

### GET /api/exchanges/{id}
Purpose: Get exchange order details.

### GET /api/sales/last-six-months
Purpose: Return last six months of sales data for charting.

---

## 4.7 Audit and operational APIs

### GET /api/audit/events
Purpose: Fetch audit events with filters.

### GET /api/audit/summary
Purpose: Fetch audit summary for security and operational review.

### GET /api/audit/export
Purpose: Export audit log CSV.

---

## 4.8 Internal / debug / testing helpers

### POST /api/login
### GET /api/test/test-db
### GET /api/test/products
### GET /api/test/generate
### GET /getLoginData
Purpose: Development and validation helper endpoints.
Notes:
- These are useful for dev/testing and not intended as core product API endpoints for frontend production flows.

---

## 5) Retail Admin Panel Requirements for Frontend

The backend is already ready for a frontend “Admin Panel to manage retail user” feature. The following needs to be implemented on the frontend side:

### 5.1 Functional requirements
- Show current admin user identity from `/api/users/me`
- Show shop or tenant profile from `/api/shop/me`
- List all retail users under the tenant via `/api/users/existing`
- Support search and pagination
- Support rename action via `/api/users/{userId}/rename`
- Support disable/enable status with `/api/users/{userId}/status`
- Support soft delete with `/api/users/{userId}`
- Support password assistance flow with `/api/users/{userId}/resend-password`
- Respect role-based UI restrictions: only store-admin-like roles can manage users

### 5.2 Suggested UI screens
1. Admin dashboard overview
2. Retail users list
3. User detail / edit drawer
4. Create new child user flow
5. Disable / delete confirmation flow
6. Security event and audit log view

### 5.3 Suggested response handling
- Show success/error banners for `success: false` and `message` payloads
- Handle rate-limit 429 responses on resend-password actions
- Handle 401/403 as session expiry or forbidden action states
- Use `tenantId` and `role` from authenticated profile to gate UI access

### 5.4 Security behavior to respect in frontend
- Do not expose admin actions to non-admin roles
- Do not allow UI to bypass tenant filters
- Clear auth state on unauthorized responses
- Keep refresh token handling consistent with backend cookie and token rotation model

---

## 6) Recommended Frontend Priorities

### Priority 1 – Admin panel foundation
- user list screen
- search and filters
- role and status display
- action buttons for rename, disable, delete, resend password

### Priority 2 – Core retail workflow
- product management
- invoice flow
- dashboard analytics
- customer flow

### Priority 3 – Security and visibility
- audit log screen for admin summary
- access control checks and tenant-level UI scoping

---

## 7) Summary for Frontend Team

The backend already contains the required building blocks for a modern retail management frontend:
- tenant-aware user management
- inventory + invoice workflows
- dashboard metrics and charts
- MFA-protected login and session management
- audit log support for operational and security visibility

The next major frontend feature should be the admin panel for managing retail users, because the backend already exposes strong user-management APIs under `/api/users` and the tenant context is already enforced securely.

This report gives the frontend team a realistic implementation map against the live backend contract, making it suitable for feature planning and delivery work.
