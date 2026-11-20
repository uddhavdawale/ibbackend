# Overall Backend Change Report

Date: 2026-10-29
Project: ibbackend (Inventory & Billing)

## 1. Executive Summary

This report consolidates all major backend changes completed in the recent implementation cycle:
- Dashboard enhancement with Top 10 mostly sold products.
- Structured audit logging with database persistence and security coverage.
- Runtime/security filter additions (request ID correlation + rate-limit trigger logging).
- Startup stability fixes introduced after audit/security changes.
- Validation status and production readiness notes.

All changes were implemented in a backward-compatible way for existing API contracts.

## 2. Dashboard: Top 10 Mostly Sold Products

### 2.1 Objective
Add top sold product ranking to dashboard responses without breaking existing frontend behavior.

### 2.2 Implementation
Added aggregate queries for both GST and Non-GST invoice items, merged and ranked in dashboard controller.

Returned in existing dashboard payloads as new field:
- topSoldProducts

Existing keys retained:
- salesToday
- pendingPayments
- lowStock
- ordersToday

### 2.3 Endpoints Affected
- GET /api/dashboard
- GET /api/dashboard/
- GET /api/dashboard/summary
- GET /api/dashboard/metrics
- GET /api/dashboard/stats

### 2.4 Response Shape
Each top sold item includes:
- productId
- name
- sku
- totalQty
- totalAmount

### 2.5 Data Rules
- Tenant-scoped.
- Cancelled invoices excluded.
- Combined across GST and Non-GST sources.
- Ranked by quantity descending, limited to top 10.

## 3. Audit Logging: Core Implementation

### 3.1 Objective
Implement auditable, structured event logging for critical security and business actions.

### 3.2 Database
Added Flyway migration:
- V6__create_audit_logs_table.sql

Created table: audit_logs with fields:
- occurred_at (UTC)
- user_id
- tenant_id
- ip_address
- user_agent
- request_id
- action
- resource_id
- outcome
- detail
- request_path
- http_method

Indexes added on:
- occurred_at
- action
- tenant_id
- user_id
- request_id

### 3.3 Core Components Added
- AuditLog entity
- AuditLogRepository
- AuditLogService
- RequestContextFilter (request ID propagation)
- SimpleRateLimitFilter (rate-limit triggers + audit)
- ApiAccessDeniedHandler
- ApiAuthenticationEntryPoint
- GlobalExceptionHandler (AccessDenied audit path)

### 3.4 Security Chain Integration
Security configuration updated to include:
- request context filter
- rate-limit filter
- JWT filter
- custom authentication entry point and access denied handler

### 3.5 Sensitive Data Safety
Improved logging hygiene:
- OTP plaintext removed from fallback logs in MailMfaService.
- No explicit logging of passwords, JWTs, refresh tokens, session cookies, CVV, or encryption keys.

## 4. Event Coverage Implemented

Implemented audit coverage for:
- Login success/failure
- MFA challenge/send/verify success/failure
- Token refresh success/failure
- Logout success/failure paths
- Access denied and invalid bearer token events
- Rate-limit trigger events
- GST invoice creation success/failure
- Non-GST invoice creation success
- Inventory upload/adjustment outcomes
- Product create/update/delete (adjustment/write-off style)
- GST summary/filings fetch as export/download style events
- Dashboard and billing fetch as admin-action style events
- Role assignment during signup (role/permission change category)

## 5. Startup and Stability Fixes

After security/audit additions, startup blockers were identified and fixed.

### 5.1 Fixed Issues
1. Bean name collision with Spring default requestContextFilter
- Resolved by renaming custom filter bean.

2. NonGstInvoiceService constructor resolution issue
- Added explicit autowired constructor for DI.
- Retained backward-compatible constructor overload for tests.

3. Security filter ordering exception
- Resolved by anchoring rate-limit filter ordering to UsernamePasswordAuthenticationFilter.

### 5.2 Result
Application starts successfully on port 8080 after fixes.

## 6. Test and Verification Summary

- Frontend contract test suite segment used during iteration passed after null-safe audit hooks:
  - FrontendContractContractTest: 5 passed.
- Spring Boot startup validation completed successfully post-fixes.

## 7. Files Added

- src/main/java/org/ib/ibbackend/entity/AuditLog.java
- src/main/java/org/ib/ibbackend/repository/AuditLogRepository.java
- src/main/java/org/ib/ibbackend/service/AuditLogService.java
- src/main/java/org/ib/ibbackend/filter/RequestContextFilter.java
- src/main/java/org/ib/ibbackend/filter/SimpleRateLimitFilter.java
- src/main/java/org/ib/ibbackend/security/ApiAccessDeniedHandler.java
- src/main/java/org/ib/ibbackend/security/ApiAuthenticationEntryPoint.java
- src/main/java/org/ib/ibbackend/exception/GlobalExceptionHandler.java
- src/main/resources/db/migration/V6__create_audit_logs_table.sql
- FRONTEND_TOP_SOLD_PRODUCTS_DASHBOARD_REPORT.md
- AUDIT_LOGGING_IMPLEMENTATION_REPORT.md

## 8. Key Files Updated

- src/main/java/org/ib/ibbackend/controller/DashBoardAPI.java
- src/main/java/org/ib/ibbackend/repository/InvoiceItemRepository.java
- src/main/java/org/ib/ibbackend/repository/NonGstInvoiceItemRepository.java
- src/main/java/org/ib/ibbackend/security/SecurityConfig.java
- src/main/java/org/ib/ibbackend/filter/JwtAuthFilter.java
- src/main/java/org/ib/ibbackend/service/MailMfaService.java
- src/main/java/org/ib/ibbackend/controller/AuthController.java
- src/main/java/org/ib/ibbackend/controller/LoginController.java
- src/main/java/org/ib/ibbackend/controller/MfaController.java
- src/main/java/org/ib/ibbackend/controller/InvoiceController.java
- src/main/java/org/ib/ibbackend/service/NonGstInvoiceService.java
- src/main/java/org/ib/ibbackend/controller/InventoryController.java
- src/main/java/org/ib/ibbackend/controller/ProductController.java
- src/main/java/org/ib/ibbackend/controller/GSTController.java
- src/main/java/org/ib/ibbackend/controller/BillingController.java

## 9. Production Go-Live Considerations (1000 Clients)

Recommended next hardening steps:
- Keep DB audit logs for compliance/security events only.
- Add retention/archival policy (for example 90/180/365 days).
- Consider monthly partitioning for audit_logs.
- Move to async audit writes if volume increases.
- Keep high-volume technical logs in external log pipeline (ELK/Loki/Cloud provider).

## 10. Closing Status

Overall change set is complete for:
- Dashboard top sold analytics integration
- Audit logging foundation and event coverage
- Startup/runtime stabilization after changes

The backend is ready for integration continuation and controlled production rollout with recommended hardening tasks planned.
