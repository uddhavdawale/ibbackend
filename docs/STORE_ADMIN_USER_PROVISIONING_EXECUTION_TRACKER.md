# Store-Admin User Provisioning Execution Tracker

Date: 2026-10-29
Project: ibbackend
Reference: BACKEND_STORE_ADMIN_USER_PROVISIONING_REPORT.md

## Status Legend
- Planned
- In Progress
- Blocked
- Done

## Ownership
- Backend Owner:
- Frontend Owner:
- QA Owner:

## 1. Backend Implementation Tracker

| ID | Task | Owner | Priority | Status | Evidence (PR/commit/test) | Notes |
|---|---|---|---|---|---|---|
| BE-01 | Resolve creator from authenticated principal for admin user-creation flow |  | P0 | Done | AuthController + UserProvisioningService + AuthControllerTenantTest (6/0) | Uses authenticated principal via resolveAuthenticatedCreator() |
| BE-02 | Enforce admin-only creation (store_admin and approved compatibility roles) |  | P0 | Done | AuthControllerTenantTest::signup_authenticatedNonAdmin_shouldReturnForbidden | Return 403 for non-admin |
| BE-03 | Normalize incoming roles to canonical role set |  | P0 | Done | UserProvisioningService::normalizeRole + UT unsupported-role 400 | Alias typo normalization included |
| BE-04 | Force child tenant from creator context (ignore request tenantId for ownership) |  | P0 | Done | AuthControllerTenantTest::signup_authenticatedAdmin_withForeignTenantInRequest_shouldIgnoreOverrideAndUseCreatorTenant | Cross-tenant override is ignored |
| BE-05 | Force child ownerName from creator context |  | P0 | Done | AuthControllerTenantTest::signup_authenticatedStoreAdmin_shouldForceCreatorTenantAndOwner | Request ownerName is advisory |
| BE-06 | Reject creation if creator tenant is null |  | P0 | Done | UserProvisioningService guard + 403 mapping in AuthController |  |
| BE-07 | Hash password server-side with Argon2/BCrypt |  | P0 | Done | PasswordSecurityService::hashNewPassword used by UserProvisioningService | Never persists raw password |
| BE-08 | Persist createdBy + createdAt (+ optional createdFromIp) |  | P1 | Planned |  | Add migration if missing |
| BE-09 | Enforce unique (tenant_id, username) policy |  | P1 | Planned |  | Add DB index/constraint |
| BE-10 | Preserve unauthenticated root signup behavior if endpoint is shared |  | P0 | Done | AuthControllerTenantTest::signup_shouldCreateUniqueTenantForEachUser | Split behavior by auth context |
| BE-11 | Emit audit log event for child-user creation |  | P1 | Planned |  | Include creator, tenant, target role |

## 2. Database / Migration Tracker

| ID | Task | Owner | Priority | Status | Evidence | Notes |
|---|---|---|---|---|---|---|
| DB-01 | Add/verify users.tenant_id NOT NULL |  | P0 | Planned |  | Backfill required before enforce |
| DB-02 | Add/verify users.created_by FK users.id |  | P1 | Planned |  | Nullable only for root registration |
| DB-03 | Add/verify users.created_at and users.updated_at |  | P1 | Planned |  |  |
| DB-04 | Add unique index on (tenant_id, username) |  | P1 | Planned |  |  |
| DB-05 | Add role check constraint or service-level strict enum validation |  | P1 | Planned |  | Canonical role set only |
| DB-06 | Backfill tenant_id for legacy null users |  | P0 | Planned |  | Data migration script |
| DB-07 | Backfill owner_name from tenant primary admin |  | P1 | Planned |  |  |

## 3. API Contract Tracker

| ID | Task | Owner | Priority | Status | Evidence | Notes |
|---|---|---|---|---|---|---|
| API-01 | Decide endpoint strategy: dedicated POST /api/users vs shared /api/auth/signup |  | P0 | Planned |  | Preferred: /api/users |
| API-02 | If shared endpoint used: split unauthenticated root signup and authenticated child-user flow |  | P0 | Done | AuthController::signup delegating to UserProvisioningService |  |
| API-03 | Ensure response excludes sensitive fields |  | P0 | Planned |  | No password hash in response |
| API-04 | Validate and sanitize optional advisory fields tenantId/ownerName |  | P0 | Done | AuthControllerTenantTest foreign-tenant and forced-owner assertions | tenantId/ownerName treated as advisory in authenticated flow |

## 4. Frontend Integration Tracker

| ID | Task | Owner | Priority | Status | Evidence | Notes |
|---|---|---|---|---|---|---|
| FE-01 | Keep sending tenantId/ownerName as advisory fields (for compatibility only) |  | P2 | Planned |  | Backend is source of truth |
| FE-02 | Handle 403 non-admin create-user responses with clear UX |  | P1 | Planned |  |  |
| FE-03 | Handle 409 duplicate username policy at tenant scope |  | P1 | Planned |  |  |
| FE-04 | Reflect canonical role names in UI role picker |  | P1 | Planned |  | Map legacy labels to canonical roles |
| FE-05 | Validate that created users display creator tenant/owner in UI listing |  | P1 | Planned |  |  |

## 5. Test Execution Tracker

### Unit Tests
| ID | Test Case | Owner | Status | Evidence | Notes |
|---|---|---|---|---|---|
| UT-01 | store_admin creates user -> child tenant equals creator tenant |  | Done | AuthControllerTenantTest::signup_authenticatedStoreAdmin_shouldForceCreatorTenantAndOwner |  |
| UT-02 | store_admin creates user -> child ownerName equals creator ownerName |  | Done | AuthControllerTenantTest::signup_authenticatedStoreAdmin_shouldForceCreatorTenantAndOwner |  |
| UT-03 | non-admin creator gets 403 |  | Done | AuthControllerTenantTest::signup_authenticatedNonAdmin_shouldReturnForbidden |  |
| UT-04 | role alias normalization to canonical roles |  | Done | AuthControllerTenantTest + unsupported-role validation test | Includes typo alias normalization |

### Integration Tests
| ID | Test Case | Owner | Status | Evidence | Notes |
|---|---|---|---|---|---|
| IT-01 | authenticated admin creates 3 users -> all share tenant_id |  | Planned |  |  |
| IT-02 | request with foreign tenantId persists creator tenant |  | Planned |  |  |
| IT-03 | request with different ownerName persists creator ownerName |  | Planned |  |  |
| IT-04 | created users login and remain tenant-scoped |  | Planned |  |  |

### Regression Tests
| ID | Test Case | Owner | Status | Evidence | Notes |
|---|---|---|---|---|---|
| RG-01 | root signup flow remains functional |  | Done | AuthControllerTenantTest::signup_shouldCreateUniqueTenantForEachUser | Targeted run on 2026-10-29: 1 passed, 0 failed |
| RG-02 | login/refresh/profile unaffected |  | Done | FrontendContractContractTest::loginPayloadShouldIncludeTenantAndBusinessMetadata + ::refreshTokenFailureShouldReturnUnauthorized + ::shopProfileEndpointShouldReturnDedicatedShopMetadata | Targeted run on 2026-10-29: FrontendContractContractTest + AuthControllerTenantTest = 11 passed, 0 failed |

## 6. Go-Live Readiness Checklist

- [ ] All P0 tasks are Done.
- [ ] DB migration applied on staging with backfill complete.
- [ ] Unit + integration + regression tests green.
- [ ] Audit logs verified for user-creation events.
- [ ] Frontend create-user flow validated with store_admin and non-admin accounts.
- [ ] Security review sign-off completed.

## 7. Final Sign-off

- Backend Lead Sign-off:
- Frontend Lead Sign-off:
- QA Sign-off:
- Security Sign-off:
- Release Date:
