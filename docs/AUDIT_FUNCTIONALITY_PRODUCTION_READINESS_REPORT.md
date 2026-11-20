# Audit Functionality Production Readiness Report

Date: 2026-11-01
Scope: Backend audit functionality for authentication, authorization, dashboard/audit access, and operational retention controls.

## 1. Executive Summary

The backend now has a production-oriented audit foundation with:
- Server-side role enforcement for audit APIs.
- Tenant-scoped audit visibility.
- Structured audit event persistence.
- Audit report and CSV export endpoints with role gates.
- Scheduled archive and purge retention lifecycle with bounded batches.

Readiness status:
- Core audit capability: Ready
- Security guardrails: Ready with a few hardening actions recommended
- Compliance evidence model: Ready
- Operational lifecycle (retention/archival): Ready
- Go-live risk level: Medium (due to environment/config hygiene tasks listed in section 11)

## 2. Implemented Production-Only Changes in This Pass

1. Added full model persistence support for compliance fields:
- actor_role
- resource_type
- metadata

2. Added retention lifecycle with scheduled archival and purge:
- Active table: audit_logs
- Archive table: audit_logs_archive
- Daily scheduled job with configurable cron/timezone
- Bounded batch processing for cost and lock safety

3. Cleaned migration for production determinism:
- Removed redundant DDL block in V9 migration

4. Tightened audit endpoint policy:
- Removed permissive endpoint-level CORS wildcard from AuditController
- Relies on centralized SecurityConfig CORS policy

## 3. Audit Architecture

### 3.1 Write Path
- AuditLogService writes events into audit_logs.
- Event origin points include:
  - Authentication lifecycle (login/refresh/logout flows)
  - Access denied and unauthorized attempts
  - Audit endpoint views and exports
  - Other controllers/services that invoke AuditLogService

### 3.2 Read Path
- AuditController provides:
  - GET /api/audit/events
  - GET /api/audit/summary
  - GET /api/audit/export
- Repository queries are filtered/paginated and tenant-aware.

### 3.3 Retention Path
- AuditRetentionService runs on schedule.
- Old rows from audit_logs are moved to audit_logs_archive in batches.
- A second batch loop purges very old archive data.

## 4. Compliance Data Model Coverage

Required model from business requirement and current implementation mapping:

1. audit_id
- Stored as audit_logs.id.
- Exposed in API as audit_id.

2. actor_user_id
- Stored as user_id.
- Exposed as actor_user_id.

3. actor_role
- Persisted at event time in actor_role.
- Exposed directly; fallback derives from user lookup when missing in legacy rows.

4. action_type
- Stored in action.
- Exposed as action_type.

5. resource_type
- Persisted at event time in resource_type.
- Exposed directly; fallback derived from request path for legacy rows.

6. resource_id
- Stored in resource_id.
- Exposed as resource_id.

7. store_id or tenant_id
- Stored in tenant_id.
- Exposed as tenant_id.

8. event_timestamp
- Stored in occurred_at.
- Exposed as event_timestamp.

9. outcome
- Stored in outcome.
- Exposed as outcome.

10. metadata
- Persisted as metadata (string JSON payload).
- Exposed as metadata.

11. ip_address or device_context
- ip_address stored from X-Forwarded-For or remote address.
- device_context stored as user_agent.
- Both exposed in APIs and CSV export.

## 5. Role and Access Control Enforcement

Allowed roles for viewing audit data:
- system_administrator
- store_admin
- audit_related

Allowed roles for exporting audit CSV:
- system_administrator
- store_admin

Enforcement behavior:
- Role checks are done server-side in AuditController before data queries.
- Unauthorized attempts are written as ACCESS_DENIED_EVENT via AuditLogService.
- Non-system roles are forced to current tenant scope.
- Cross-tenant access attempts are denied and audited.

## 6. Tenant and Data Isolation

Controls in place:
- TenantContext and current authenticated user resolve effective tenant.
- Audit queries filter by tenant unless role is system-level.
- Explicit tenantId query parameter is rejected for non-system users when mismatched.

Operational impact:
- Store-level users cannot fetch another tenant's audit history.
- System administrators can request global view or scoped tenant view.

## 7. Endpoint Contracts

### 7.1 GET /api/audit/events
Purpose: Paginated audit events list.

Parameters:
- page (default 0)
- size (default 50, max 200)
- from (ISO-8601 instant or yyyy-MM-dd)
- to (ISO-8601 instant or yyyy-MM-dd)
- actionType
- outcome
- tenantId (system role only)

Response includes:
- items
- page, size, totalItems, totalPages
- scopeTenantId
- role
- hasMore

### 7.2 GET /api/audit/summary
Purpose: Summary metrics for compliance dashboards.

Parameters:
- days (default 30, max 365)
- tenantId (system role only)

Response includes:
- totalChecks
- recentAccessEvents
- exceptionCount
- complianceScore
- windowDays
- scopeTenantId
- role

### 7.3 GET /api/audit/export
Purpose: CSV report export.

Parameters:
- from, to, actionType, outcome, tenantId
- maxRows (default 1000, max 5000)

CSV columns:
- audit_id
- actor_user_id
- actor_role
- action_type
- resource_type
- resource_id
- tenant_id
- event_timestamp
- outcome
- ip_address
- device_context
- endpoint
- http_method
- metadata

Safety behavior:
- Export size is capped.
- X-Export-Truncated indicates if rows exceeded cap.

## 8. Database and Migration Status

### 8.1 Existing base table
- V6 migration created audit_logs with core columns and indexes.

### 8.2 New production migration
- V9 migration adds:
  - metadata, actor_role, resource_type columns on audit_logs
  - audit_logs_archive table
  - archive indexes for retention and reporting
  - actor/resource indexes on active table

### 8.3 Storage lifecycle
- active data: audit_logs
- warm/cold historical data: audit_logs_archive

## 9. Retention and Archival Policy

Configurable properties:
- app.audit.retention.enabled
- app.audit.retention.cron
- app.audit.retention.zone
- app.audit.retention.active-retention-days
- app.audit.retention.archive-retention-days
- app.audit.retention.batch-size
- app.audit.retention.max-batches-per-run

Default runtime policy:
- Active retention: 180 days
- Archive retention: 730 days
- Batch size: 1000 rows
- Max batches per run: 20
- Schedule: Daily at 02:15 UTC

Cost and safety controls:
- Batching avoids heavy table locks and long transactions.
- Oldest-first processing keeps active table lean.
- Purge is also batched.

## 10. Security Controls in Context

1. Authentication and authorization:
- SecurityFilterChain requires authentication for all non-permitted routes.
- Unauthorized and forbidden responses are handled centrally and audited.

2. No-cache for sensitive data:
- RequestContextFilter sets no-store/no-cache headers for API routes.
- Audit endpoints also apply explicit no-store headers.

3. Request correlation:
- RequestContextFilter sets/propagates X-Request-Id.
- request_id is included in audit entries.

4. Audit abuse resistance:
- Role gates on view/export.
- Tenant scope enforcement.
- Export row limit.

## 11. Production Gaps and Required Hardening Tasks

These are required before high-confidence production launch:

P0 (must fix before go-live):
1. Remove plaintext default credentials/secrets from application defaults.
- SMTP and datasource values currently have concrete fallback credentials.
- Replace with environment-only required values and fail fast if missing.

2. Set spring.jpa.hibernate.ddl-auto to validate (or none) in production profile.
- Current value is update, which can perform unintended schema changes.

3. Define audit data classification and masking policy.
- Ensure detail and metadata never contain secrets, OTPs, tokens, or raw PII beyond policy.

P1 (strongly recommended):
1. Add archival integrity controls.
- Optional hash/signature per row or per export for tamper-evidence.

2. Add retention observability.
- Metrics for rows archived, rows purged, cycle duration, and failures.

3. Add archive query endpoint or restore workflow for compliance investigations.

## 12. Performance and Cloud Cost Considerations

Current cost-friendly design:
- Paginated event access with strict max page size.
- Time-window filtering at DB query level.
- Export caps to avoid large memory payloads.
- Scheduled retention to reduce active index/table bloat.
- Indexed query paths for common filters.

Expected cloud impact:
- Moderate write amplification from auditing is expected and controlled.
- Daily retention job load is bounded by batch settings.

## 13. Test and Verification Status

Verified in current workspace:
- AuditControllerTest: pass
- FrontendContractContractTest: pass
- AuditRetentionServiceTest: pass

Validated outcomes:
- Role-gated access works.
- Export restrictions work.
- Existing frontend contract tests remain green.
- Retention job logic executes with bounded batch behavior.

## 14. Go-Live Runbook (Audit)

1. Pre-deploy
- Apply Flyway migrations through V9 in staging.
- Verify archive table and indexes exist.
- Set production env variables for audit retention policy.
- Remove insecure config defaults in prod profile.

2. Deployment
- Deploy backend with scheduling enabled.
- Confirm startup logs include scheduling and security chain initialization.

3. Post-deploy checks
- Trigger a known audited action (login, denied access).
- Verify record in audit_logs with tenant_id, actor_role, resource_type, metadata.
- Test /api/audit/events as store_admin and audit_related.
- Test /api/audit/export denied for audit_related.

4. Daily operations
- Monitor retention cycle logs.
- Track archived and purged counts.
- Alert on repeated retention failures.

## 15. Final Assessment

The backend audit functionality is now broadly production-ready for role-based audit visibility, compliance reporting, and retention lifecycle management.

To reach full production compliance posture, complete the P0 hardening tasks in section 11, especially secret/config hygiene and strict production JPA mode.
