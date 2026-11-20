# Inventory Ledger Flow Detailed Report

Date: 2026-10-29
Project: ibbackend

## 1. Objective
This document explains the inventory ledger flow end-to-end: how stock mutations are validated, applied, and journaled so each change is traceable.

## 2. Ledger Architecture Overview

Core components:
- `InventoryMovement` entity (journal record)
- `inventory_movements` table (Flyway V8)
- `InventorySecurityService.applyMovement(...)` (central stock mutation gate)

Design intent:
- Every stock-changing operation should produce one movement record.
- Movement records capture before and after quantity so net effect is auditable.

## 3. Data Model (Movement Record)
Each movement stores:
- Product: `product_id`
- Tenant: `tenant_id`
- User (when resolvable): `user_id`
- Type: `movement_type`
- Delta: `delta_qty` (positive add, negative deduct)
- Snapshot: `before_qty`, `after_qty`
- Reference: `reference_type`, `reference_id`
- Note: human-readable reason/context
- Time: `created_at`

Movement types currently used:
- `SALE`
- `SALE_CANCEL`
- `ADJUSTMENT`
- `WRITE_OFF`
- `UPLOAD`

## 4. Central Mutation Algorithm

All secure stock changes route through `applyMovement(...)`:

1. Resolve tenant from `TenantContext`.
2. If tenant missing, reject with `403`.
3. Optional approval gate check (`requireApproval`): when true, reject until approved path exists.
4. Lock product row using tenant-aware query `findByIdAndTenantIdForUpdate(...)`.
5. Read `beforeQty` from current product row.
6. Compute `afterQty = beforeQty + deltaQty`.
7. If `afterQty < 0`, reject with validation error (insufficient stock).
8. Persist product with `afterQty`.
9. Persist movement row with all metadata.
10. Commit transaction atomically.

Guarantees:
- No negative stock from this path.
- Product quantity and movement journal stay consistent in a single transaction.
- Tenant-isolated lock and update behavior.

## 5. Flow by Business Operation

### 5.1 GST Sell Order Creation
Entry point:
- `POST /api/invoices`

Per item:
- Calls stock deduction helper -> `applyMovement(..., deltaQty = -qty, type = SALE, referenceType = INVOICE, referenceId = invoiceNo)`

Effect:
- Product stock decreases.
- One `SALE` ledger row per item.

### 5.2 GST Sell Order Cancellation
Entry point:
- `POST /api/invoices/{id}/cancel`

Per item:
- Calls restore helper -> `applyMovement(..., deltaQty = +qty, type = SALE_CANCEL, referenceType = INVOICE_CANCEL, referenceId = invoiceNo)`

Effect:
- Product stock restores.
- One `SALE_CANCEL` ledger row per item.
- Idempotent cancellation handling in controller for already-cancelled invoices.

### 5.3 Non-GST Sell Order Creation
Entry point:
- `POST /api/non-gst-invoices`

Per item:
- `applyMovement(..., deltaQty = -qty, type = SALE, referenceType = NON_GST_INVOICE, referenceId = invoiceNo)`

Effect:
- Product stock decreases.
- One `SALE` ledger row per item.

### 5.4 Non-GST Sell Order Cancellation
Entry point:
- `POST /api/non-gst-invoices/{id}/cancel`

Per item:
- `applyMovement(..., deltaQty = +qty, type = SALE_CANCEL, referenceType = NON_GST_INVOICE_CANCEL, referenceId = invoiceNo)`

Effect:
- Product stock restores.
- One `SALE_CANCEL` ledger row per item.

### 5.5 Product Create with Initial Quantity
Entry point:
- `POST /api/products`

If initial quantity > 0:
- `applyMovement(..., deltaQty = initialQty, type = ADJUSTMENT, referenceType = PRODUCT_CREATE, referenceId = productId)`

Effect:
- Product initial stock is journaled as adjustment.

### 5.6 Product Update with Quantity Change
Entry point:
- `PUT /api/products/{id}`

Controller computes:
- `delta = targetQty - beforeQty`

If `delta != 0`:
- `applyMovement(..., deltaQty = delta, type = ADJUSTMENT, referenceType = PRODUCT_UPDATE, referenceId = productId)`

Effect:
- Up/down stock changes are captured with before/after snapshots.

### 5.7 Product Delete with Remaining Stock
Entry point:
- `DELETE /api/products/{id}`

If quantity > 0:
- `applyMovement(..., deltaQty = -qty, type = WRITE_OFF, referenceType = PRODUCT_DELETE, referenceId = productId)`

Effect:
- Remaining stock is reduced to zero before delete.
- Ledger records write-off event.

### 5.8 Inventory Upload
Entry point:
- `POST /api/inventory/upload`

Behavior:
- Service compares existing qty vs uploaded qty after save.
- Computes `deltaQty = afterQty - beforeQty`.
- If delta non-zero, writes movement with type `UPLOAD`, reference `INVENTORY_UPLOAD`.

Effect:
- Bulk import stock changes are journaled row-by-row.

## 6. Concurrency and Integrity Notes

### 6.1 Locking
- Product row lock is taken in secure mutation flow using tenant-scoped for-update query.
- Prevents race conditions during simultaneous sell/cancel/update operations.

### 6.2 Transactional consistency
- Product update + movement insert run in one transaction.
- Prevents orphan stock updates without ledger or ledger without stock update.

### 6.3 Tenant isolation
- Product lookup for mutation is tenant-scoped.
- Cross-tenant product mutation is blocked.

## 7. Read/Query Patterns for Ops and Audit

Common useful filters:
- By `tenant_id` and date window (`created_at`)
- By `product_id` for product history
- By `reference_type` + `reference_id` for order-level trace

Useful reconciliation equation:
- `after_qty = before_qty + delta_qty`

For any product timeline, this invariant should always hold per movement row.

## 8. Example Movement Timeline
Example product P in tenant T:

1. Product created with qty 100
- `ADJUSTMENT`, delta `+100`, before `0`, after `100`

2. GST invoice sold qty 4
- `SALE`, delta `-4`, before `100`, after `96`

3. Non-GST invoice sold qty 6
- `SALE`, delta `-6`, before `96`, after `90`

4. GST invoice cancelled for qty 4
- `SALE_CANCEL`, delta `+4`, before `90`, after `94`

5. Upload sets qty to 120
- computed delta `+26`, type `UPLOAD`, before `94`, after `120`

6. Product deleted with qty 120
- `WRITE_OFF`, delta `-120`, before `120`, after `0`

This timeline provides complete stock explainability.

## 9. Current Limitations and Next Enhancements

1. Full approval workflow is not yet implemented (request/approve/reject model).
2. There is no dedicated read API yet for fetching movement history by product/tenant.
3. Operational dashboards for movement anomaly detection can be added.

## 10. Recommended Next Implementation Step
Add inventory movement query APIs:
- `GET /api/inventory/movements?productId=&from=&to=`
- `GET /api/inventory/movements/reference?type=&id=`

This will let frontend and operations teams inspect stock history directly without DB access.
