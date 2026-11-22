# Customer, Invoice, Payment, and Balance Flow

This report documents the backend behavior to preserve when changing customer balances, GST/non-GST invoicing, payment collection, refunds, and invoice/Sales responses. It reflects the implementation at commit `baac27e` and the regression tests added with that change.

## Core accounting rules

1. Invoice rows are the source of truth for customer receivables. A customer's due is the sum of the outstanding amounts on their active GST and non-GST invoices.
2. For each invoice:

   ```text
   invoiceDue = max(grandTotal - paidAmount, 0)
   ```

3. Customer due is:

   ```text
   customerDue = sum(GST invoiceDue) + sum(non-GST invoiceDue)
   ```

   Cancelled invoices are excluded. Paying a new invoice in full clears that invoice's due; it does not clear an older unpaid invoice. Therefore, a prior due remains when a new invoice is paid in full.
4. Customer-profile balance aliases (`dueBalance`, `balanceDue`, `outstandingBalance`, `pendingAmount`, `pendingBalance`, `amountDue`, `totalDue`, `balance`, `receivable`, and `outstanding`) must be updated together through `CustomerProfile.applyOutstandingAmount`.
5. Client-supplied `previousBalance`, `balanceAmount`, `currentBalance`, or `totalAmount` must not be used as authoritative ledger values. These values can be stale or tampered with. The backend derives dues from persisted invoices.

## Customer identity and previous balance

`CustomerProfileService` associates invoice customers within a tenant:

- A real phone number is the primary match.
- If no usable phone is present, matching and ledger lookup fall back to the trimmed customer name.
- Phone values consisting only of zeroes (for example, `0000000000`) are placeholders, not customer identifiers. They must not merge different customers' histories.
- If an invoice has neither a usable phone nor a customer name, invoice persistence should continue without creating a shared/anonymous customer profile.
- A new customer starts with zero previous due unless matching active invoices exist for that customer.

Matching without a real phone is limited: two different people with the same name cannot be reliably distinguished. Do not broaden name matching or invent a new identity rule without an explicit product decision.

## Payment status contract

`paymentStatus` is the canonical payment state for both GST and non-GST invoices. `status` is the coarser invoice/order state; a partially paid invoice remains `PENDING` until fully settled.

| Paid amount | `paymentStatus` | `status` | Invoice due |
|---|---|---|---|
| Zero | `PENDING` | `PENDING` | `grandTotal` |
| Greater than zero, less than total | `PARTIAL` | `PENDING` | `grandTotal - paidAmount` |
| Equal to total (or capped at total) | `PAID` | `PAID` | Zero |
| Cancelled | `CANCELLED` | `CANCELLED` | Excluded from customer due |

### GST invoice creation

`InvoiceController.createInvoice`:

- Uses `paymentStatus` when supplied; otherwise it falls back to `status`.
- Supports `PAID`, `PENDING`, and `PARTIAL` intents (including the existing aliases).
- A `PAID` intent records the invoice total, not a client-supplied overpayment.
- A `PENDING` intent records zero received.
- A `PARTIAL` intent uses the received amount, capped at the invoice total.
- Derives the saved payment state from the resulting paid amount.
- Saves the invoice, records its initial payment, then synchronizes the customer profile in the same transaction.
- GST creation requires a recognized payment intent; an omitted or unrecognized intent is rejected.

The GST request DTO accepts `paidAmount`, `amountPaid`, and `receivedAmount`. An explicitly supplied `paidAmount` takes precedence; otherwise the aliases are used in that order.

### Non-GST invoice creation

`NonGstInvoiceService.createNonGstInvoice` applies the same status priority and amount bounds. It derives `paymentStatus` from the saved paid amount and stores `status=PAID` only when the invoice is fully settled; partial and unpaid invoices use `status=PENDING`. It then records the initial payment and synchronizes the customer profile.

The non-GST DTO supports the same paid-amount aliases. Do not let the GST and non-GST paths independently interpret those fields or status intents.

Non-GST creation currently permits an omitted status and then derives state from the supplied paid amount. It also accepts `CANCELLED` as a creation intent, whereas GST cancellation is performed through the cancellation endpoint. Avoid relying on either edge case for new clients; prefer explicit payment intent and the cancellation endpoint. If these contracts are changed, update both handlers and the tests together.

### Later collections, refunds, and cancellations

- `PaymentCollectionService` locks the tenant-scoped invoice before collecting or refunding.
- Collections increase `paidAmount`; refunds decrease it without allowing a negative result.
- After either operation, the invoice's `paymentStatus` and `status` are recalculated using the contract above, and the customer ledger is refreshed.
- GST and non-GST cancellation paths set both states to `CANCELLED` and refresh the customer ledger.
- Initial payments and later payments are also represented by tenant-scoped `PaymentRecord` rows. Keep those records consistent with the invoice's aggregate `paidAmount`.

## Profile and invoice/Sales reads

- Customer list, customer-by-ID, and customer-by-phone reads calculate the current due from the invoice ledgers before returning the profile.
- Customer list includes customers found in invoice records even if a profile row does not exist yet.
- GST invoice summaries and non-GST invoice list/detail responses expose payment state and amounts. Keep `paymentStatus`, `paidAmount`, and invoice outstanding values consistent with the table above.
- The GST list currently formats `status` in title case, while non-GST responses use uppercase. `paymentStatus` is uppercase in both; consumers should use it for the payment state and avoid case-sensitive comparisons on `status`.
- Customer profile synchronization persists the calculated ledger value when invoices are created or payments are processed. Read endpoints recalculate the response but do not write to the database.
- The invoice request fields named `previousBalance` and `balanceAmount` are not an invoice balance snapshot contract. If a future UI needs a server-calculated previous-balance snapshot on the invoice itself, add an explicit response field/DTO and define its transaction-time semantics; do not trust a client-provided value.

## Tenant, security, and production constraints

- Every customer and invoice lookup/aggregate must remain scoped to the current tenant. Never aggregate balances across tenants.
- Continue to derive the tenant from authenticated `TenantContext` for reads and updates; do not use a client-supplied tenant ID to bypass tenant authorization.
- Security follow-up: `CustomerProfileController.upsertCustomer` currently prefers an optional `tenantId` from the request body, while the customer GET/PUT/PATCH routes use the current tenant. Treat that POST behavior as a tenant-isolation hardening item; validate the requested tenant against the authenticated tenant or derive it from context before extending this route.
- Payment collection/refund authorization, row locking, idempotency, and amount validation are part of the payment safety boundary. Do not bypass them when adding a payment path.
- Do not add a second balance ledger or accept client-calculated due values without an explicit migration and reconciliation plan.
- Current customer-list calculation uses tenant-scoped invoice aggregates for GST and non-GST balances. Avoid adding per-invoice round trips or new cloud services. If tenant sizes make list reads expensive, measure first and replace the per-customer aggregate pattern with tenant-scoped batch aggregation while preserving the same rules.
- No schema migration is required for the current balance behavior.

## Future change checklist

Before changing this flow:

1. Identify whether the change affects invoice creation, collection, refund, cancellation, profile sync, or response mapping.
2. Apply the same payment-state rules to GST and non-GST paths.
3. Preserve tenant scoping, locks, idempotency, and invoice-derived balance calculation.
4. Add focused tests for the affected lifecycle in both bill types where applicable. Include new customer/placeholder phone, old due plus a fully paid new invoice, partial payment, full payment, refund, and cancellation cases as appropriate.
5. Run the focused backend tests:

   ```powershell
   .\mvnw.cmd "-Dtest=CustomerProfileServiceTest,FrontendContractContractTest,NonGstInvoiceServiceTest,PaymentCollectionServiceTest" test
   ```

6. Update this report in the same change if the accounting rules, API contract, or security/performance constraints change.

## Implementation references

- `src/main/java/org/ib/ibbackend/service/CustomerProfileService.java`
- `src/main/java/org/ib/ibbackend/service/InvoiceService.java`
- `src/main/java/org/ib/ibbackend/service/NonGstInvoiceService.java`
- `src/main/java/org/ib/ibbackend/service/PaymentCollectionService.java`
- `src/main/java/org/ib/ibbackend/controller/InvoiceController.java`
- `src/main/java/org/ib/ibbackend/repository/InvoiceRepository.java`
- `src/main/java/org/ib/ibbackend/repository/NonGstInvoiceRepository.java`
- `src/main/java/org/ib/ibbackend/entity/CustomerProfile.java`
- `src/main/java/org/ib/ibbackend/entity/Invoice.java`
- `src/main/java/org/ib/ibbackend/entity/NonGstInvoice.java`
- `src/test/java/org/ib/ibbackend/service/CustomerProfileServiceTest.java`
- `src/test/java/org/ib/ibbackend/service/NonGstInvoiceServiceTest.java`
- `src/test/java/org/ib/ibbackend/service/PaymentCollectionServiceTest.java`
- `src/test/java/org/ib/ibbackend/controller/FrontendContractContractTest.java`
