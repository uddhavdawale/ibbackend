# Inventory Security Change Report

Date: 2026-10-29
Project: ibbackend

## 1. Scope
This report summarizes the inventory security and stock-control changes implemented in this release.

## 2. Major Changes Implemented

### 2.1 Immutable Inventory Ledger
Implemented a dedicated movement ledger table and entity:
- `inventory_movements` table
- `InventoryMovement` entity
- `InventoryMovementRepository`

Movement types currently used:
- `SALE`
- `SALE_CANCEL`
- `ADJUSTMENT`
- `WRITE_OFF`
- `UPLOAD`

Ledger columns captured for each mutation:
- `product_id`, `tenant_id`, `user_id`
- `movement_type`
- `delta_qty`
- `before_qty`
- `after_qty`
- `reference_type`
- `reference_id`
- `note`
- `created_at`

Migration:
- `src/main/resources/db/migration/V8__create_inventory_movements_table.sql`

### 2.2 Centralized Stock Mutation Service
Added `InventorySecurityService.applyMovement(...)` as the controlled path for stock mutations.

Security controls in this service:
- Tenant enforcement using `TenantContext`
- Pessimistic row locking via `findByIdAndTenantIdForUpdate(...)`
- Negative-stock prevention (`afterQty < 0` validation)
- Atomic transaction boundary with `@Transactional`
- Ledger append for every successful movement

### 2.3 Product API Hardening (Mass-Assignment Reduction)
Product create/update now use explicit DTO:
- `ProductUpsertRequest`

Controller now maps allowed fields explicitly and applies secure defaults.
This reduces accidental or malicious over-posting of entity fields.

### 2.4 Invoice and Cancellation Flows Secured
Stock mutations for invoice create/cancel now route through controlled movement logic.

Implemented for:
- GST sell order create
- GST sell order cancel
- Non-GST sell order create
- Non-GST sell order cancel

Fallback logic remains in place for test/manual-instantiation paths where inventory service injection is intentionally absent.

### 2.5 Inventory Upload Ledger Integration
Bulk upload now writes ledger entries when quantity changes:
- Movement type: `UPLOAD`
- Captures before/after quantity and delta
- Tags reference as `INVENTORY_UPLOAD`

### 2.6 Tenant-Scoped Invoice Number Uniqueness
Added tenant-scoped uniqueness for invoice numbers:
- GST invoices: `(tenant_id, invoice_no)`
- Non-GST invoices: `(tenant_id, invoice_no)`

Migration:
- `src/main/resources/db/migration/V7__scope_invoice_number_uniqueness_to_tenant.sql`

## 3. API Impact Summary

### Product APIs
- `POST /api/products`
- `PUT /api/products/{id}`
- `DELETE /api/products/{id}`

Behavior changes:
- Explicit DTO payload expectation for create/update
- Stock initialization/adjustment/write-off logged through movement ledger

### GST Invoice APIs
- `POST /api/invoices`
- `POST /api/invoices/{id}/cancel`

Behavior changes:
- Stock deduction/restoration tracked in ledger
- Tenant-aware and lock-protected mutation path

### Non-GST Invoice APIs
- `POST /api/non-gst-invoices`
- `POST /api/non-gst-invoices/{id}/cancel`

Behavior changes:
- Stock deduction/restoration tracked in ledger
- Tenant-aware and lock-protected mutation path

### Inventory Upload API
- `POST /api/inventory/upload`

Behavior changes:
- Stock change operations from upload are journaled in ledger

## 4. Validation and Test Status
Targeted regression tests were run after these changes:
- `FrontendContractContractTest`
- `NonGstInvoiceControllerTest`
- `NonGstInvoiceServiceTest`

Result:
- 8 passed, 0 failed

## 5. Security Checklist Mapping
This implementation directly improves:
- Controlled stock change entry point
- Concurrency-safe stock updates (locking + transaction)
- Immutable-style movement history (append-only usage)
- Tenant isolation in mutation and lookup paths
- DTO-based request hardening for product write endpoints

## 6. Remaining Gap / Next Step
Approval workflow is scaffolded but not fully implemented yet.
Current service has a `requireApproval` gate parameter. A complete request-approve-execute workflow (table + APIs + role checks) can be added in the next phase.

## 7. Changed Files (Inventory Security Scope)
- `src/main/java/org/ib/ibbackend/entity/InventoryMovement.java`
- `src/main/java/org/ib/ibbackend/repository/InventoryMovementRepository.java`
- `src/main/java/org/ib/ibbackend/service/InventorySecurityService.java`
- `src/main/java/org/ib/ibbackend/dto/ProductUpsertRequest.java`
- `src/main/java/org/ib/ibbackend/controller/ProductController.java`
- `src/main/java/org/ib/ibbackend/controller/InvoiceController.java`
- `src/main/java/org/ib/ibbackend/service/NonGstInvoiceService.java`
- `src/main/java/org/ib/ibbackend/service/InventoryUploadService.java`
- `src/main/java/org/ib/ibbackend/service/InvoiceService.java`
- `src/main/resources/db/migration/V7__scope_invoice_number_uniqueness_to_tenant.sql`
- `src/main/resources/db/migration/V8__create_inventory_movements_table.sql`

## 12. Dashboard Security

Implemented controls:
- Dashboard/report endpoints are now authenticated (not public).
- Dashboard data remains tenant-scoped and now carries authenticated `userId`, `tenantId`, and `role` context in response payloads.
- Role-aware response shaping is applied in dashboard stats (non-admin users do not receive full product-expiry list payload).
- API cache sharing safeguards are enforced with `no-store` + `private` + `Vary: Authorization, Cookie, X-Tenant-Id` headers.
- Export/report request frequency is rate-limited for dashboard and GST endpoints.
- Report/list response size controls are enforced via max page sizes and bounded search length.

IDOR and predictable ID protections strengthened:
- Tenant-scoped lookup continues for invoice/product endpoints.
- Invoice stock fallback path now enforces tenant-aware product ownership checks before deduction.
- Billing products endpoint switched from global product listing to tenant-scoped listing.

Aggregate query and tenant filter alignment:
- Dashboard metrics/stats use tenant-filtered repository queries.
- GST summary switched to tenant-filtered revenue/tax calculation.
- Sales trend repository/service now queries by month **and tenant**.

Files involved for dashboard security hardening:
- `src/main/java/org/ib/ibbackend/security/SecurityConfig.java`
- `src/main/java/org/ib/ibbackend/controller/DashBoardAPI.java`
- `src/main/java/org/ib/ibbackend/filter/RequestContextFilter.java`
- `src/main/java/org/ib/ibbackend/filter/SimpleRateLimitFilter.java`
- `src/main/java/org/ib/ibbackend/controller/BillingController.java`
- `src/main/java/org/ib/ibbackend/controller/GSTController.java`
- `src/main/java/org/ib/ibbackend/service/SellService.java`
- `src/main/java/org/ib/ibbackend/repository/SalesRepository.java`
- `src/main/java/org/ib/ibbackend/controller/InvoiceController.java`
- `src/main/java/org/ib/ibbackend/controller/ProductController.java`
