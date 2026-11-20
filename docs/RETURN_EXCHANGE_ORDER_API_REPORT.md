# Return and Exchange Order API Report

## Overview
This module adds a simple, reusable workflow for customer return and exchange operations for invoices created in this backend.

## API Endpoints

### Return orders
- POST `/api/returns`
- GET `/api/returns`
- GET `/api/returns/{id}`

Example request body:

```json
{
  "invoiceId": 12,
  "reason": "Damaged item",
  "items": [
    {
      "productId": 5,
      "productName": "Rice",
      "quantity": 1,
      "unitPrice": 100.00
    }
  ]
}
```

### Exchange orders
- POST `/api/exchanges`
- GET `/api/exchanges`
- GET `/api/exchanges/{id}`

Example request body:

```json
{
  "invoiceId": 12,
  "reason": "Wrong size",
  "newInvoiceAmount": 150.00,
  "items": [
    {
      "productId": 5,
      "productName": "Rice",
      "quantity": 1,
      "unitPrice": 100.00
    }
  ]
}
```

## Behavior
- Validates the invoice and requested quantities before processing.
- Restores returned stock into inventory and records the movement in the inventory log.
- Stores return amount or exchange credit in the corresponding entity.
- Restricts access by tenant in the same way as the rest of the backend modules.

## Notes
This keeps the module easy to use by following the existing project conventions:
- entity
- dto
- repository
- service
- controller

The service layer handles validation and stock updates, while the controller exposes a clean REST contract for frontend or internal clients.
