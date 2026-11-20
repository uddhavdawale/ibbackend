# Spring Boot Inventory Upload Backend Report

## Overview
This report documents the current backend implementation for bulk inventory import. The live implementation is already in the controller and service layer and is the contract that the frontend must follow.

The endpoint accepts either:
- a multipart upload file, or
- a JSON `records` payload inline in the request body,

and then validates and persists inventory rows under the authenticated tenant context.

---

## Current API Contract

### Endpoint
POST /api/inventory/upload

### Content-Type
multipart/form-data

### Request parameters
- `file`: MultipartFile (required if `records` is not provided)
- `tenantId`: Long (optional; validated against the authenticated tenant)
- `userId`: Long (optional; must match the authenticated user)
- `source`: String (optional)
- `records`: String (optional; JSON array of rows used as an alternative to file upload)

### Security requirement
This endpoint is protected by JWT authentication. Requests without a valid bearer token are rejected before the controller is reached.

### Example request with file
```bash
curl -X POST "http://localhost:8080/api/inventory/upload" \
  -H "Authorization: Bearer <token>" \
  -F "file=@inventory.xlsx" \
  -F "source=inventory-upload" \
  -F "tenantId=1" \
  -F "userId=12"
```

### Example request with inline records
```bash
curl -X POST "http://localhost:8080/api/inventory/upload" \
  -H "Authorization: Bearer <token>" \
  -F "source=inventory-upload" \
  -F "tenantId=1" \
  -F 'records=[{"product":"Rice","sku":"RICE-001","qty":20,"minStock":5,"mrp":250,"price":250,"category":"Groceries","hsnSac":"1001","set":1,"poNumber":"PO-01"}]'
```

---

## Supported file formats
The implementation supports:
- CSV
- XLS
- XLSX

The file validation logic blocks unsupported extensions and rejects oversize uploads.

---

## Current required / normalized columns
The service maps headers using a flexible normalization strategy. It accepts common aliases and normalizes them internally.

### Supported header keys and aliases
- Product / Item / Name
- SKU / Product SKU
- HSN/SAC / HSN / HSN SAC
- MRP/Item / MRP / MRP Item
- Qty / Quantity
- Min Stock / MinStock
- Set
- Price / Price/QTY / Unit Price
- PO Number / PO / PONumber
- Category / Categories

### Normalized row fields used by the service
```json
{
  "product": "Rice",
  "sku": "RICE-001",
  "hsnSac": "1001",
  "poNumber": "PO-01",
  "qty": 20,
  "minStock": 5,
  "set": 1,
  "mrp": 250,
  "price": 250,
  "pricePerQty": 250,
  "category": "Groceries"
}
```

---

## Validation rules in the current implementation
The current code validates rows before saving:

- `product` is required and limited to 200 chars
- `hsnSac` is required and limited to 100 chars
- `sku` is required and limited to 100 chars
- `qty` must be a non-negative integer
- `minStock` must be a non-negative integer
- `mrp` must be a positive number
- `price` must be a positive number
- `pricePerQty` must be positive if present
- `set` must be a positive integer
- `category` defaults to `General` if blank
- upload size is capped at 5 MB (or the configured value)
- maximum rows per upload is 5000 (or configured value)
- duplicate rows in the same payload are skipped by sku

### Required columns enforced by the service
The code explicitly checks these required header groups:
- Item
- HSN/SAC
- MRP/ Item
- QTY
- Min Stock

If any are missing, the upload fails with a validation exception.

---

## Tenant and user resolution
The implementation enforces tenant ownership during upload.

### Behavior
- The authenticated user is resolved from the security context or from `userId`.
- The tenant is resolved from the current tenant context or the authenticated user tenant.
- If `tenantId` is provided, it must match the resolved tenant.
- If `userId` is provided, it must match the authenticated user.
- If the user is not assigned to a tenant, the upload fails.

This means the upload is effectively tenant-scoped and cannot be used across tenant boundaries.

---

## Current controller behavior
The controller is implemented in [src/main/java/org/ib/ibbackend/controller/InventoryController.java](src/main/java/org/ib/ibbackend/controller/InventoryController.java).

### Response format on success
Status: 200 OK

```json
{
  "success": true,
  "message": "Inventory uploaded successfully",
  "created": 25,
  "updated": 3,
  "skipped": 0,
  "records": [
    {
      "id": 101,
      "product": "Rice",
      "sku": "RICE-001",
      "qty": 20,
      "minStock": 5,
      "price": 250,
      "mrp": 250,
      "set": 1,
      "poNumber": "PO-01",
      "hsnSac": "1001",
      "pricePerQty": 250,
      "category": "Groceries"
    }
  ]
}
```

### Response format on validation failure
Status: 400 Bad Request

```json
{
  "success": false,
  "message": "Inventory upload validation failed",
  "errors": [
    "Column Item is required",
    "Qty must be a non-negative number",
    "MRP/ Item must be a positive number"
  ]
}
```

### Response format on auth failure
Status: 403 or 401 depending on security flow

```json
{
  "message": "Invalid or expired token"
}
```

---

## Data persistence behavior
The current implementation persists to the `Product` entity and uses tenant-scoped product lookup.

### Persist logic
- Finds an existing product by `sku` and tenant if a tenant is resolved.
- Creates a new `Product` if not found.
- Sets product fields such as name, sku, hsnSac, qty, minStock, price, mrp, setSize, poNumber, category, and tenant.
- Saves the product and counts it as create or update.
- Returns the saved records list with the created/updated product metadata.

### Important implementation detail
The method does not use a separate upload staging table or generic inventory entity. It writes directly to the `Product` repository.

---

## Current implementation notes
The actual implementation currently lives in:
- [src/main/java/org/ib/ibbackend/controller/InventoryController.java](src/main/java/org/ib/ibbackend/controller/InventoryController.java)
- [src/main/java/org/ib/ibbackend/service/InventoryUploadService.java](src/main/java/org/ib/ibbackend/service/InventoryUploadService.java)
- [src/main/java/org/ib/ibbackend/security/SecurityConfig.java](src/main/java/org/ib/ibbackend/security/SecurityConfig.java)
- [src/main/java/org/ib/ibbackend/filter/JwtAuthFilter.java](src/main/java/org/ib/ibbackend/filter/JwtAuthFilter.java)

This means the backend contract differs slightly from the older conceptual report:
- auth is mandatory,
- tenant validation is enforced,
- the allowed fields are broader than a simple product-only CSV,
- file parsing and validation are concrete and implementation-driven, not generic placeholders.

---

## Summary
The current upload endpoint is a tenant-aware, JWT-protected, multipart-based bulk import endpoint for `Product` records. It accepts CSV/XLS/XLSX files or inline JSON records, validates them strictly, and persists them to the product table under the authenticated tenant.

This report reflects the actual code path currently in the project rather than the earlier proposed design.
