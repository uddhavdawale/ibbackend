# Cash Collection Tickets API

Cash collection tickets are cash-custody handovers to a store admin. Customer payments remain recorded against their invoices when collected; a ticket does not create a second payment or change invoice balances.

Ticket records use the existing handover ledger. Balance tracking adds a small tenant configuration and an audited opening-balance table; no background job is required.

## Submit a ticket

`POST /api/cash-collection-tickets`

Existing JSON requests remain supported for cash submissions. For all payment methods, the multipart request is:

`Content-Type: multipart/form-data`

Fields:

- `recipientUserId`: numeric user ID or unique active store-admin name, username, or email
- `declaredAmount`: amount submitted
- `idempotencyKey`: unique retry key
- `notes`: optional note
- `paymentMethod`: `CASH`, `UPI`, `CARD`, or `UPI_CARD` (`UPI/Card` is also accepted)
- `paymentProof`: image file required for UPI/card; PNG, JPEG, or WebP, up to 5 MB

The existing JSON contract defaults `paymentMethod` to `CASH`. Electronic submissions must use multipart and include `paymentProof`; screenshots are validated by their file signature, encrypted before storage, and never included in ticket-list responses. Cash submissions must not include a payment screenshot.

```json
{
  "recipientUserId": "vaishali",
  "declaredAmount": 1250.00,
  "idempotencyKey": "cash-drop-2026-11-17-001",
  "paymentMethod": "CASH",
  "notes": "Evening shift cash"
}
```

For UPI/card, `paymentProof` is required. The authenticated non-admin cashier must have sufficient unassigned cash only for `CASH`; pending electronic tickets do not reserve physical cash. Once accepted or resolved, an electronic ticket reduces the sender's outstanding due but does not add to the recipient's physical cash balance. `recipientUserId` accepts a numeric user ID or an exact, case-insensitive active store-admin name, username, or email in the authenticated tenant. Name/username/email matches must be unique. A cash amount is reserved from the submitter while the ticket is pending.

The response is `201 Created` with the ticket fields described below, status `PENDING`, and `senderAvailableCash`.

## List valid recipients

`GET /api/cash-collection-tickets/recipients`

Only authenticated billing managers and store managers can use this selector. It returns active store admins in the caller's tenant as `{ "content": [{ "id": 12, "name": "Store Admin" }] }`. Ticket creation still validates the recipient and tenant on the server.

## Read cashier or tenant balances

`GET /api/cash-collection-tickets/balances?page=0&size=100`

Billing managers and store managers receive their own `balance` object; the endpoint does not accept a user ID. Store admins and system admins with an explicit tenant context receive a paginated list of tenant cash collectors and a server-calculated tenant-wide `summary`. Page size is capped at 100 and rows are ordered by due descending, then name and ID. Pending and disputed cash-ticket holds are subtracted only from `availableToSubmit`; disputed cash holds use the lesser of the declaration and counted amount. Accepted/resolved electronic tickets reduce the sender's due, but never contribute to a recipient's physical cash balance or cash reconciliation totals.

Tracking starts automatically from the earliest eligible cashier onboarding timestamp in the tenant. A missing opening-balance ledger entry means zero due; cash collections and handovers are then calculated from the payment and handover ledgers. A newly onboarded biller therefore sees zero due before making cash sales, and the balance endpoint does not require separate admin setup or per-cashier zero-value writes. The system never infers cash from invoices or non-cash payments.

The legacy admin-only `POST /api/cash-collection-tickets/balances/initialize` and `/balances/opening` routes remain available for explicit rollout reconciliation. Reconcile any historical cash already held before enabling this workflow for existing cashiers. Automatic tracking setup is tenant-scoped, idempotent, and recorded as system onboarding rather than as an admin approval.

The cashier response includes cash payment count and amount collected since the most recent settled outgoing handover, cash refunds, carried-forward due, total due, pending/disputed cash holds, and available amount. Admin totals are calculated across the full tenant, not just the current page. Physical cash balances and reconciliation totals use cash payment, refund, cash handover, and opening-balance records; accepted/resolved electronic tickets reduce the sender's due only.

## List tickets

`GET /api/cash-collection-tickets?page=0&size=20`

Optional `status` filter: `PENDING`, `ACCEPTED`, `DISPUTED`, or `RESOLVED`. Page is zero-based; size defaults to `50` and is capped at `100`.

Store admins and system admins can review all tickets in their tenant. Cashiers see only tickets they submitted. Results are tenant-scoped and ordered newest first.

Example response:

```json
{
  "content": [
    {
      "id": 31,
      "senderUserId": 11,
      "senderName": "Example Cashier",
      "recipientUserId": 12,
      "recipientName": "Store Admin",
      "declaredAmount": 1250.00,
      "paymentMethod": "CASH",
      "paymentProofAvailable": false,
      "paymentProofUrl": null,
      "receiverCountedAmount": null,
      "resolvedAmount": null,
      "status": "PENDING",
      "notes": "Evening shift cash",
      "recipientNotes": null,
      "resolutionNote": null,
      "resolvedByUserId": null,
      "senderConfirmedAt": "2026-11-17T14:00:00Z",
      "recipientConfirmedAt": null,
      "resolvedAt": null,
      "createdAt": "2026-11-17T14:00:00Z",
      "updatedAt": "2026-11-17T14:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalPages": 1,
  "totalElements": 1
}
```

If there are no tickets, `content` is an empty array and `totalElements` is `0`. Ticket fields can include null values before counting or resolution.

## Review a payment screenshot

The designated store-admin recipient, the submitting biller, or a store/system admin with tenant access can retrieve a submitted proof:

`GET /api/cash-collection-tickets/{ticketId}/payment-proof`

The response is the original image with its detected image content type and private/no-store caching. The ticket's `paymentProofUrl` is non-null only when a proof was submitted. Access is tenant-scoped; unrelated users receive `404`.

After review, the designated store-admin recipient uses the existing `/count` action to accept an exact amount or dispute a mismatch. A store admin or system admin can then resolve a disputed ticket. Accepted/resolved UPI/card amounts reduce the sender's due without changing the recipient's physical cash balance or cash reconciliation totals.

## Count and accept, or dispute

Only the designated store-admin recipient can count a ticket:

`POST /api/cash-collection-tickets/{ticketId}/count`

```json
{
  "countedAmount": 1250.00,
  "idempotencyKey": "cash-count-2026-11-17-001",
  "notes": "Counted at the store"
}
```

An exact count changes status to `ACCEPTED` and transfers the amount from the cashier's available cash to the store admin's available cash. The response includes `storeCashAvailable`, the admin's resulting available cash balance.

A different count changes status to `DISPUTED`. No money is added to the store admin's available cash while disputed.

## Resolve a disputed ticket

Only a store admin or system admin can resolve a dispute:

`POST /api/cash-collection-tickets/{ticketId}/resolve`

```json
{
  "resolvedAmount": 1240.00,
  "idempotencyKey": "cash-resolution-2026-11-17-001",
  "resolutionNote": "Verified the drawer count"
}
```

The resolved amount cannot exceed the declared or counted amount. The resolved amount moves to the admin's available cash; any remainder stays with the cashier. The response includes the resulting `storeCashAvailable`. The payment report remains unchanged because the customer payment was recorded at collection time.

## Ticket fields

`id`, `senderUserId`, `senderName`, `recipientUserId`, `recipientName`, `declaredAmount`, `paymentMethod`, `paymentProofAvailable`, `paymentProofUrl`, `receiverCountedAmount`, `resolvedAmount`, `status`, `notes`, `recipientNotes`, `resolutionNote`, `resolvedByUserId`, `senderConfirmedAt`, `recipientConfirmedAt`, `resolvedAt`, `createdAt`, and `updatedAt`.

Payment proofs are encrypted in PostgreSQL using the existing `TENANT_DOCUMENT_ENCRYPTION_KEY` setting; no public object URL or additional cloud storage provider is used. Configure the key in the backend deployment environment. Cash-only submissions do not require it.

## Errors

- `401 Unauthorized` or `403 Forbidden`: unauthenticated, wrong-tenant, or insufficient role.
- `400 Bad Request`: invalid amount, invalid request, non-admin recipient, or unsupported status.
- `404 Not Found`: recipient/ticket not found in the tenant.
- `409 Conflict`: insufficient unassigned cash, duplicate idempotency key with different values, or ticket in an invalid state.
