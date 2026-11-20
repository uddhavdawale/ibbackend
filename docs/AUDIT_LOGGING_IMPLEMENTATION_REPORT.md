# Audit Logging Implementation Report

Date: 2026-10-29
Project: ibbackend

## 1. Objective

Implemented structured audit logging with a dedicated database table and runtime instrumentation for security and business events, while preserving existing API behavior.

## 2. Database Changes

Added Flyway migration:
- V6__create_audit_logs_table.sql

Table created:
- audit_logs

Captured fields in table:
- occurred_at (UTC timestamp)
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

Indexes added for:
- occurred_at
- action
- tenant_id
- user_id
- request_id

## 3. New Backend Components

Entity and repository:
- AuditLog entity
- AuditLogRepository

Core service:
- AuditLogService

Request context:
- RequestContextFilter
  - Creates/propagates X-Request-Id
  - Adds requestId to request attributes and response header

Rate limit:
- SimpleRateLimitFilter
  - Protects auth-sensitive endpoints
  - 60 requests/minute per IP+path
  - Returns HTTP 429 on trigger
  - Logs RATE_LIMIT_TRIGGER audit event

Security handlers:
- ApiAccessDeniedHandler
- ApiAuthenticationEntryPoint

Global handler:
- GlobalExceptionHandler for AccessDeniedException audit logging

## 4. Instrumented Event Coverage

Implemented:
- Login success/failure
- MFA challenge + verify success/failure
- Signup failures and role assignment during signup
- Token refresh success/failure
- Logout success/failure paths
- Access-denied events
- Invalid bearer token / malformed auth header events
- Rate-limit triggers
- GST invoice create success/failure
- Non-GST invoice create success
- Inventory upload/adjustment outcomes
- Product create/update/delete (adjustment + write-off style events)
- GST summary/filings fetch as export/download style events
- Dashboard and billing fetch as admin action events

## 5. Sensitive Data Protection

Fixed:
- Removed plaintext OTP value from logs in MailMfaService fallback mode.

Never-logged policy enforced by implementation style:
- No password values logged
- No JWT token values logged
- No refresh token values logged
- No session cookie values logged
- No encryption keys logged

## 6. Compatibility Notes

- Existing endpoint contracts were not changed.
- Audit calls are null-safe where unit tests instantiate controllers directly without Spring DI.
- Existing frontend contract tests pass.

## 7. Validation Result

Executed test file:
- FrontendContractContractTest

Result:
- 5 passed, 0 failed

## 8. Files Added

- src/main/java/org/ib/ibbackend/entity/AuditLog.java
- src/main/java/org/ib/ibbackend/repository/AuditLogRepository.java
- src/main/java/org/ib/ibbackend/service/AuditLogService.java
- src/main/java/org/ib/ibbackend/filter/RequestContextFilter.java
- src/main/java/org/ib/ibbackend/filter/SimpleRateLimitFilter.java
- src/main/java/org/ib/ibbackend/security/ApiAccessDeniedHandler.java
- src/main/java/org/ib/ibbackend/security/ApiAuthenticationEntryPoint.java
- src/main/java/org/ib/ibbackend/exception/GlobalExceptionHandler.java
- src/main/resources/db/migration/V6__create_audit_logs_table.sql

## 9. Files Updated

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
- src/main/java/org/ib/ibbackend/controller/DashBoardAPI.java
- src/main/java/org/ib/ibbackend/controller/BillingController.java

## 10. Remaining Optional Enhancements

These can be added if/when corresponding features exist:
- Explicit password change/reset endpoints with dedicated PASSWORD_CHANGE and PASSWORD_RESET events
- Invoice update/cancel/refund events if those endpoints are added
- Dedicated role/permission management endpoint auditing beyond signup-time role assignment
- Stronger distributed rate limiter (Redis or gateway-based) for multi-instance deployments

## 11. Production Rollout Checklist

- Run Flyway migration in staging and production (`V6__create_audit_logs_table.sql`).
- Validate `audit_logs` table indexes are present after migration.
- Confirm request correlation header is returned (`X-Request-Id`) in API responses.
- Trigger sample events (login success/failure, MFA failure, rate-limit) and verify records inserted.
- Enable retention strategy (archive/purge policy) before high-volume rollout.
- Restrict DB access so only service account and authorized admin roles can query audit data.

## 12. Recommended Retention and Scale Strategy

- Keep only compliance/security/business audit events in `audit_logs`.
- Do not store high-volume debug/application logs in this table.
- Suggested retention baseline: 180 days (adjust by compliance requirement).
- For 1000+ clients, plan monthly partitioning of `audit_logs` by `occurred_at`.
- Monitor insert latency and table growth; move to async write if request latency increases.

## 13. Verification Queries (PostgreSQL Examples)

Latest audit entries:

```sql
SELECT id, occurred_at, tenant_id, user_id, action, resource_id, outcome, request_id
FROM audit_logs
ORDER BY occurred_at DESC
LIMIT 50;
```

Failed login attempts in last 24 hours:

```sql
SELECT occurred_at, tenant_id, user_id, ip_address, action, outcome, detail
FROM audit_logs
WHERE action = 'LOGIN'
  AND outcome = 'FAILURE'
  AND occurred_at >= NOW() - INTERVAL '24 hours'
ORDER BY occurred_at DESC;
```

Rate-limit triggers:

```sql
SELECT occurred_at, ip_address, request_path, action, outcome, detail
FROM audit_logs
WHERE action = 'RATE_LIMIT_TRIGGER'
ORDER BY occurred_at DESC;
```
