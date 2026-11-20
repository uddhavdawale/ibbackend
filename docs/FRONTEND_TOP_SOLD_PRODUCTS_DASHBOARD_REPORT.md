# Frontend Top Sold Products Dashboard Report

Date: 2026-10-28
Project: Inventory & Billing Backend (ibbackend)
Audience: Frontend Team

## 1. Objective

This report documents the backend contract for Top 10 mostly sold products, now included in dashboard API responses.

Goal:
- Let dashboard fetch summary metrics and top sold products in the same request.
- Keep existing API behavior unchanged for current frontend users.

## 2. What Changed (Backward Compatible)

Added one new response field:
- `topSoldProducts`

No existing keys were removed or renamed.

Existing keys remain:
- `salesToday`
- `pendingPayments`
- `lowStock`
- `ordersToday`

## 3. Endpoints Updated

Top sold data is now included in these dashboard responses:

1. `GET /api/dashboard/metrics`
2. `GET /api/dashboard/summary` (already mapped to metrics internally)
3. `GET /api/dashboard` (root mapping, same as summary/metrics)
4. `GET /api/dashboard/stats`

## 4. New Field Contract

Field name:
- `topSoldProducts`

Type:
- array of objects

Item schema:
- `productId`: number | null
- `name`: string
- `sku`: string
- `totalQty`: number
- `totalAmount`: number

Meaning:
- `totalQty`: total sold units (GST + Non-GST invoices combined)
- `totalAmount`: summed line item total amount for that product

Ranking:
- sorted by `totalQty` descending
- limited to top 10 records

Filtering:
- tenant-scoped (current logged-in tenant only)
- cancelled invoices are excluded

## 5. Example Response (Metrics)

```json
{
  "salesToday": 12800,
  "pendingPayments": 3200,
  "lowStock": 6,
  "ordersToday": 14,
  "topSoldProducts": [
    {
      "productId": 12,
      "name": "Notebook A5",
      "sku": "NB-A5-01",
      "totalQty": 145,
      "totalAmount": 18250.00
    },
    {
      "productId": 4,
      "name": "Blue Pen",
      "sku": "PEN-BL-10",
      "totalQty": 112,
      "totalAmount": 2240.00
    }
  ]
}
```

## 6. Frontend Integration Notes

1. Continue calling existing dashboard endpoint; no URL change required.
2. Read `topSoldProducts` from response and bind to a dashboard widget/table/chart.
3. Keep fallback handling:
- If field missing or empty, show "No sales data available" state.
4. Render quantity ranking primarily using `totalQty`.
5. Optionally show `totalAmount` as secondary column.

Recommended defensive read:
- `const topSold = Array.isArray(data.topSoldProducts) ? data.topSoldProducts : [];`

## 7. UI Suggestion

For quick dashboard card/table:
- Column 1: Rank (#1 to #10)
- Column 2: Product Name
- Column 3: SKU
- Column 4: Units Sold (`totalQty`)
- Column 5: Sales Amount (`totalAmount`)

## 8. Non-Functional Notes

- This is additive and non-breaking for existing clients.
- Existing frontend contract tests for dashboard keys continue to pass.
- Aggregation includes both GST and Non-GST invoice item sources.

## 9. Future Optional Enhancements

Can be added later without breaking current integration:
- configurable limit (`limit=10`)
- date range filter (e.g., last 30 days)
- dedicated endpoint (`/api/dashboard/top-sold`)
- include product image/category in payload

## 10. Backend Reference (for traceability)

Primary implementation points:
- `src/main/java/org/ib/ibbackend/controller/DashBoardAPI.java`
- `src/main/java/org/ib/ibbackend/repository/InvoiceItemRepository.java`
- `src/main/java/org/ib/ibbackend/repository/NonGstInvoiceItemRepository.java`
