# Payment Report API

The payment report is available at `GET /api/payments`. Cash custody submission and review use the separate `GET` and `POST /api/cash-collection-tickets` endpoints documented in [CASH_COLLECTION_TICKETS_RESPONSE_REPORT.md](./CASH_COLLECTION_TICKETS_RESPONSE_REPORT.md).

## Request

Requires an authenticated tenant member allowed to view collections.

| Parameter | Optional | Description |
|---|---|---|
| `from` | Yes | Start date, inclusive, ISO `YYYY-MM-DD`; defaults to today |
| `to` | Yes | End date, inclusive, ISO `YYYY-MM-DD`; defaults to today |
| `collectorId` | Yes | Filter by collector; store admins and billing managers may select one |
| `method` | Yes | `CASH`, `UPI`, `CARD`, `BANK`, `WALLET`, `OTHER`, or `ALL` |
| `invoiceId` | Yes | Filter by GST or non-GST invoice ID |
| `page` | Yes | Zero-based page; defaults to `0` |
| `size` | Yes | Page size; defaults to `50`, capped at `100` |

Dates use the retailer timezone (`Asia/Kolkata`). Invalid or reversed dates and ranges longer than 366 days are rejected. All results are scoped to the authenticated tenant. Billing viewers are always restricted to their own collections, even if they provide another `collectorId`.

Example:

```http
GET /api/payments?from=2026-11-17&to=2026-11-17&page=0&size=20
```

## Response

The JSON response contains `from`, `to`, `summary`, `payments`, `page`, `size`, `totalPages`, and `totalElements`. Each payment row includes its invoice and receipt details, collector, method, status, source, timestamp, and refundable amount.

`summary.totalCollected` is net of refunds; refund rows are reported as negative payment amounts. `summary.totalRefunds` is a positive total. `summary.outstandingAmount` is the tenant-wide outstanding balance, independent of the report date and filters.

## Related endpoints

- `POST /api/invoices/{invoiceId}/payments` — collect against a GST invoice
- `POST /api/non-gst-invoices/{invoiceId}/payments` — collect against a non-GST invoice
- `POST /api/payments/{paymentId}/refunds` — refund a payment
- `GET /api/cash-reconciliations` — paginated cash reconciliation history
