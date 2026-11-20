# Cash handover API

All endpoints are tenant-scoped from the authenticated user's token. Amounts are decimal currency values with at most two fractional digits.

## Submit a handover

`POST /api/cash-handovers`

```json
{
  "recipientUserId": 22,
  "declaredAmount": 1250.00,
  "idempotencyKey": "shift-2026-11-17-001",
  "notes": "Evening shift cash"
}
```

The sender must have sufficient available cash. The declared amount is reserved immediately, and the handover remains `PENDING` until the recipient counts it.

## List and retrieve handovers

- `GET /api/cash-handovers?status=PENDING&page=0&size=50`
- `GET /api/cash-handovers/{handoverId}`

Cash handlers can view handovers in which they participate. Store admins, billing managers, and system admins can view the tenant's handovers.

## Confirm, dispute, or resolve

`POST /api/cash-handovers/{handoverId}/confirm`

```json
{
  "countedAmount": 1250.00,
  "idempotencyKey": "receive-2026-11-17-001",
  "notes": "Counted in front of sender"
}
```

An exact count changes the status to `ACCEPTED`; a different count changes it to `DISPUTED`. Only the designated recipient may confirm.

`POST /api/cash-handovers/{handoverId}/resolve`

```json
{
  "resolvedAmount": 1240.00,
  "idempotencyKey": "resolve-2026-11-17-001",
  "resolutionNote": "Verified drawer count and approved transfer of 1240.00"
}
```

Only a store admin or system admin can resolve a dispute. The resolved amount cannot exceed either the declaration or the recipient's count. Idempotency keys make retries safe; repeated confirmations or resolutions with different values are rejected.

Cash availability reserves pending and disputed outgoing handovers, subtracts accepted/resolved outgoing cash, and adds accepted/resolved incoming cash. Reconciliation totals include completed incoming and outgoing handovers.

For staff cash deposits that must be counted by a store admin, use the dedicated `/api/cash-collection-tickets` workflow documented in [CASH_COLLECTION_TICKETS_RESPONSE_REPORT.md](./CASH_COLLECTION_TICKETS_RESPONSE_REPORT.md). It shares the cash allocation ledger but restricts the recipient to a store admin.
