# Frontend Dashboard Security Implementation Report

Date: 2026-10-29
Project: ibbackend
Audience: Frontend Agent / Frontend Team

## 1. Purpose
This report is the implementation handoff for frontend work after backend dashboard-security hardening.
It explains exactly what changed on backend and what frontend must do next.

## 2. Backend Security Changes You Must Assume Are Active

### 2.1 Authentication is now required for business APIs
Sensitive APIs are no longer public. Frontend must always send a valid bearer token.

Impacted families:
- /api/dashboard/**
- /api/products/**
- /api/invoices/**
- /api/non-gst-invoices/**
- /api/billing/**
- /api/gst/**
- /api/sales/**

### 2.2 Tenant/user/role-aware dashboard responses
Dashboard responses now include security context fields:
- tenantId
- userId
- role

Role-aware behavior:
- non-admin users may receive reduced data for some dashboard detail sections (for example product-expiry list in stats).

### 2.3 API anti-cache hardening
API responses include no-store/no-cache behavior and vary-by-auth headers.
Frontend must never assume shared cached API payloads are reusable across users.

### 2.4 Export/report frequency limits
Dashboard and GST/report style endpoints now have request throttling.
Rapid repeated calls can return HTTP 429.

### 2.5 Page-size and extraction limits
List/report endpoints now enforce caps to reduce mass extraction.
Examples:
- products list page size is capped
- invoices list page size is capped
- billing products page size is capped

### 2.6 Tenant-safe data scoping improved
Billing products, sales trend, and GST summary use tenant-filtered data paths.
Frontend should expect strictly tenant-scoped numbers and records.

## 3. Frontend Implementation Tasks (Actionable)

### 3.1 Auth/session handling
- Always attach Authorization: Bearer <accessToken> for all protected APIs.
- On 401/403, clear session and redirect to login flow.
- Keep refresh flow ready before dashboard bootstrapping.

### 3.2 Dashboard module
- Read and safely use tenantId/userId/role from dashboard payload.
- Hide or degrade UI sections when role is not STORE_ADMIN.
- Do not assume products list inside dashboard stats is always populated.
- Add graceful empty-state UX for restricted/trimmed payload sections.

### 3.3 Reports and analytics module
- Add retry/backoff policy for 429 responses.
- Debounce dashboard/gst refresh triggers (avoid spam calls).
- Disable aggressive polling for report widgets.
- Add user feedback: "Too many requests, retry in a few seconds" for 429.

### 3.4 Pagination controls
- Never hardcode large page sizes.
- Keep UI page size within backend limits.
- Handle backend-enforced cap silently (if UI asks 500 and backend returns capped page).

### 3.5 Caching in frontend app
- Do not persist shared dashboard/report data across different signed-in users.
- Clear in-memory stores on logout/tenant switch.
- If using query caches (React Query, SWR, etc.), include user and tenant identity in query keys.

### 3.6 Invoice and billing views
- Continue using ID-based routes, but rely only on backend-returned tenant-safe data.
- Handle not-found/forbidden states cleanly without exposing guessed IDs in UI errors.

## 4. Endpoint Contract Notes for Frontend

### Dashboard
- GET /api/dashboard/metrics
- GET /api/dashboard/stats
- GET /api/dashboard/summary

Expect:
- Auth required
- Tenant-scoped values
- Security context fields in payload
- No-cache headers
- Possible 429 when spammed

### GST/reporting
- GET /api/gst/summary
- GET /api/gst/filings

Expect:
- Auth required
- Tenant-scoped aggregates
- No-cache headers
- Possible 429 when spammed

### Billing products
- GET /api/billing/products?page=&size=

Expect:
- Auth required
- Tenant-scoped product page
- Size capped by backend

## 5. Error Handling Matrix (Frontend)

- 401 Unauthorized: token missing/expired/invalid -> refresh or relogin
- 403 Forbidden: cross-tenant/permission issue -> show access denied state
- 404 Not Found: resource not in tenant scope -> show safe not-found message
- 409 Conflict: uniqueness/business conflict -> show actionable message
- 429 Too Many Requests: report/export frequency exceeded -> backoff and retry

## 6. QA Checklist for Frontend Agent

1. Login and open dashboard: verify metrics load only with token.
2. Remove token and call dashboard: verify app handles 401 path.
3. Trigger rapid dashboard refresh: verify 429 handling UX.
4. Open GST summary/filings repeatedly: verify throttling handling.
5. Validate dashboard payload contains tenantId/userId/role.
6. Test with non-admin role: verify restricted sections degrade gracefully.
7. Fetch billing products with very large size: verify UI handles backend-capped result.
8. Logout and login as another tenant/user: verify no old cached dashboard data is shown.

## 7. Recommended Frontend Agent Execution Order

1. Update API client interceptors for 401/403/429 globally.
2. Patch dashboard state model to include role-aware rendering.
3. Patch report widgets with debouncing + retry backoff.
4. Patch pagination defaults in products/invoices/billing modules.
5. Run regression on session switch and cache invalidation.

## 8. Non-Negotiable Rules

- Never trust client-side tenant filtering for security.
- Never share cached API responses across users/tenants.
- Never assume unrestricted report/download frequency.
- Never assume full dataset payloads for non-admin roles.

## 9. Handoff Summary
Backend security changes are compatible with normal frontend flows, but frontend must now be strict about:
- authenticated API usage,
- 429/403 handling,
- role-aware rendering,
- safe cache partitioning/clearing,
- bounded pagination.

This is sufficient for the frontend agent to continue implementation safely.