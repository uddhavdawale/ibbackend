# WhatsApp Cloud API setup

The WhatsApp callback is shared by the platform. Each retailer configures a distinct
Meta phone-number ID, WABA ID, display number, and access token from **Profile >
Integrations**. Inbound webhook events are assigned to a shop only through the
configured phone-number ID; a tenant ID in a request or message is never used for
routing. Only an active store administrator whose registered phone matches the
sender can receive a reply.

## Required server secrets

Configure these values in the deployment secret manager; do not put them in the
frontend environment or commit them:

- `WHATSAPP_APP_SECRET`: Meta App Secret used to verify `X-Hub-Signature-256`.
- `WHATSAPP_WEBHOOK_VERIFY_TOKEN`: random value used for Meta's GET verification.
- `WHATSAPP_CREDENTIAL_ENCRYPTION_KEY`: base64-encoded 32-byte AES key used to
  encrypt each retailer's access token at rest. Generate it once (for example,
  with `openssl rand -base64 32`) and keep a protected backup. Rotating this key
  requires re-encrypting existing tokens before replacing the old key.
- `APP_WHATSAPP_WEBHOOK_URL`: public HTTPS URL ending in
  `/api/v1/webhooks/whatsapp`, shown in the integration page when configured.

The Meta webhook URL and verify token are platform-wide. Subscribe the Meta app
to the `messages` field. The page never returns a saved access token; submitting
an empty token when updating the same connected account preserves its encrypted token.
The per-shop access token must have the Meta permissions needed to inspect the
configured WhatsApp phone number and send WhatsApp business messages. Settings
are checked with Meta before the server stores them.

## Persistence and processing

Flyway migration V43 adds a one-row-per-tenant integration table and a durable
webhook event queue. A unique phone-number ID prevents connecting one Meta
business number to multiple shops. Message/status event IDs are unique per
phone-number ID, and the PostgreSQL worker claims events with `SKIP LOCKED` so
multiple app instances do not concurrently claim the same event. Failed delivery
is retried with bounded exponential delay (five attempts); event payloads are
deleted after 30 days.

The current first slice verifies signatures, persists/deduplicates events, and
replies to a registered shop owner's inbound text with the starter greeting.
It does not yet execute billing commands, call an AI model, or create invoices;
those actions require their own customer/product resolution and explicit,
tenant-bound confirmation workflow before enabling financial writes.
