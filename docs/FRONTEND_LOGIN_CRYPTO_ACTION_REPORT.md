# Frontend Action Report — Encrypted Login (RSA+AES) Decrypt Failures

Date: 2026-11-03
Backend file: `src/main/java/org/ib/ibbackend/service/LoginCryptoService.java`

## 1. Summary

Backend already supports encrypted login (`encryptedKey`, `encryptedPayload`, `iv`) and attempts RSA decrypt of the AES key in this order:

1. `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`
2. `RSA/ECB/OAEPWithSHA-1AndMGF1Padding` (legacy fallback)
3. `RSA/ECB/PKCS1Padding` (legacy fallback)

If **all three fail**, backend throws `InvalidLoginCryptoPayloadException` → login request rejected with:
```
Unable to decrypt encrypted key. Check frontend public key pair and RSA mode/padding
```

Since the public/private key pair has been confirmed to match, the failure is almost certainly a **binary-to-base64 encoding bug on the frontend**, not a key or padding mismatch.

## 2. Root Cause (Frontend)

When converting the RSA-encrypted `ArrayBuffer` (ciphertext bytes) to a base64 string, using any UTF-8-aware string conversion (e.g. `TextDecoder`, `unescape(encodeURIComponent(...))`, or improper `String.fromCharCode` usage with large buffers) **corrupts the raw bytes**, because ciphertext is not valid UTF-8 text.

This produces a base64 string that *decodes* without error on the backend, but the resulting bytes no longer match the original RSA ciphertext — causing `BadPaddingException` / decrypt failure across all 3 padding modes.

## 3. Required Frontend Fix

Use a byte-safe `ArrayBuffer → base64` conversion for **all three fields**: `encryptedKey`, `encryptedPayload`, `iv`.

```js
function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}
```

Example usage after RSA-OAEP encryption of the AES key:

```js
const encryptedKeyBuffer = await crypto.subtle.encrypt(
  { name: "RSA-OAEP" },
  publicKey,
  aesRawKeyBytes
);

const encryptedKeyB64 = arrayBufferToBase64(encryptedKeyBuffer);
```

Apply the same function to:
- `encryptedPayload` (AES-GCM ciphertext bytes)
- `iv` (12-byte IV)

## 4. What NOT to do

- ❌ `new TextDecoder().decode(buffer)` then base64-encode the decoded string
- ❌ `unescape(encodeURIComponent(binaryString))`
- ❌ `String.fromCharCode(...new Uint8Array(buffer))` with large buffers (spread can corrupt/truncate on some engines)
- ❌ Any JSON stringify/parse round-trip on binary buffers before encoding

All of the above assume UTF-8 text content, which binary ciphertext is not.

## 5. Verification Checklist (Frontend)

- [ ] Replace base64 encoding logic for `encryptedKey`, `encryptedPayload`, and `iv` with the byte-safe loop method above
- [ ] Confirm `crypto.subtle.encrypt({ name: "RSA-OAEP" }, publicKey, aesKeyBytes)` uses **SHA-256** hash (default for Web Crypto `RSA-OAEP`) to match backend's primary mode
- [ ] Confirm IV is exactly 12 random bytes (`crypto.getRandomValues(new Uint8Array(12))`)
- [ ] Re-test login — backend should now succeed on the **first** RSA-OAEP SHA-256 attempt (no fallback warnings in backend logs)

## 6. Backend Reference (No Changes Needed)

Backend decrypt contract (`LoginCryptoService.decrypt`):

```json
{
  "encryptedPayload": "<base64 AES-GCM ciphertext>",
  "encryptedKey": "<base64 RSA-OAEP(SHA-256) encrypted AES key>",
  "iv": "<base64, 12 bytes>"
}
```

- AES: `AES-GCM`, 128-bit tag, key size 16/24/32 bytes
- RSA: `OAEP SHA-256` (preferred), with SHA-1/PKCS1 fallbacks kept for legacy compatibility only

Once the frontend encoding fix is applied, no backend changes are required — the primary RSA-OAEP SHA-256 path will succeed directly.

## 7. Status

- [x] Backend key-pair match confirmed
- [x] Backend already supports 3 RSA padding modes with clear failure logging
- [ ] **Frontend action required:** fix binary→base64 conversion for encrypted login fields
- [ ] Re-test end-to-end login after frontend fix

