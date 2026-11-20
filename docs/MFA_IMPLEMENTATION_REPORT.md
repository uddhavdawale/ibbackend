# Email MFA Backend

## Status

The backend supports an email OTP challenge after password authentication. Mail MFA is controlled by `APP_MFA_MAIL_ENABLED`; the email delivery service must also be configured. Login does not issue access or refresh tokens until OTP verification succeeds.

## Flow

1. `POST /api/login` validates the credentials. If mail MFA is enabled, it returns an `mfaChallenge` containing a random `challengeId` and masked destination, and sets a short-lived `HttpOnly`, `Secure`, `SameSite=None` `mfaPreAuth` cookie scoped to `/api/mfa`.
2. The client explicitly requests delivery with `POST /api/mfa/send` and `{ "challengeId": "..." }`. The cookie and ID must identify the same active challenge.
3. The client verifies with `POST /api/mfa/verify` and `{ "challengeId": "...", "otp": "123456" }`. A successful, one-time verification issues the regular login session fields and an HttpOnly, Secure, `SameSite=None` refresh cookie, then clears the pre-authentication cookie. The refresh token is not returned in the response body.
4. The client can cancel with `POST /api/mfa/cancel` and `{ "challengeId": "..." }`. Cancellation consumes the challenge, clears its OTP, and clears the pre-authentication cookie.

Challenge and verification responses use `Cache-Control: no-store`. Email addresses are returned only in masked form; the full destination is used only by the mail delivery service.

## Challenge protections

- Challenge records are persisted in the `auth_challenges` table; OTPs are stored as keyed HMAC digests, not plaintext.
- A random pre-authentication secret is stored only as a digest and bound to the challenge ID.
- OTP verification and cancellation lock the challenge row. Successful verification consumes the OTP and challenge atomically.
- Challenges and OTPs expire. Each challenge allows at most three sends, with a 30-second resend cooldown and five failed verification attempts. Auth endpoint rate limiting also applies to send, verify, and cancel.
- The client must send the returned challenge ID; username-based or cookie-only challenge lookup is not accepted by the email flow.

The HMAC key is configured through `APP_MFA_OTP_HMAC_SECRET` and must contain at least 32 bytes. Configure an independent production secret rather than relying on the compatibility fallback.

## Configuration and operations

    Relevant settings include `APP_MFA_MAIL_ENABLED`, `APP_MFA_MAIL_OTP_LENGTH`, `APP_MFA_MAIL_EXPIRY_MINUTES`, `APP_MFA_CHALLENGE_EXPIRY_MINUTES`, and `APP_MFA_OTP_HMAC_SECRET`. Email is sent with the Brevo HTTP API when `BREVO_API_KEY` is configured. `APP_MAIL_PROVIDER` accepts `auto` (default; prefer the API when its key is set), `brevo-api`, or `smtp`. SMTP fallback uses the `SMTP_*` settings and Spring Mail.

The API key is read from the `BREVO_API_KEY` environment variable through `app.mail.brevo.api-key` in `src/main/resources/application.properties`. Set `BREVO_API_KEY` in the deployment platform's environment/secrets settings; do not commit the key into the properties file. Set `APP_MAIL_PROVIDER=brevo-api` to require API delivery explicitly.

Email MFA is the only supported MFA flow; Firebase MFA has been removed.

## Application logging

MFA and forgot/reset-password lifecycle events are written to the application logger, with the request correlation ID included in console and file log patterns. Development mode uses the `dev` Spring profile for DEBUG-level application diagnostics; production mode uses the `prod` profile and INFO-level application logging, with warnings and errors retained.

Activate a profile with `SPRING_PROFILES_ACTIVE=dev` or `SPRING_PROFILES_ACTIVE=prod`. The default configuration is INFO-level and does not enable DEBUG logging.

Application logs do not include email addresses, OTP values, reset tokens, passwords, bearer tokens, or cookie values. Forgot-password requests retain the same generic response regardless of whether an account matches. Structured audit events remain separate from application diagnostics.

Brevo API failures log only the message type and HTTP status (or a connection-error marker), never the API key, request payload, recipient address, or response body.

## Validation

Focused tests cover challenge-cookie binding, missing challenge IDs, one-time OTP consumption, cancellation, and the login challenge response contract. Run them with:

```powershell
.\mvnw.cmd "-Dtest=AuthChallengeServiceTest,MfaControllerTest,LoginControllerMfaContractTest" test
```

End-to-end deployment validation should also cover SMTP delivery, cookie attributes over HTTPS, challenge replay and expiry, send/attempt throttling, and cross-origin behavior.
