# Frontend Email MFA Implementation Report

## Status

The frontend email-MFA sign-in flow is implemented. It lets the user complete a second-factor challenge after password authentication, before the application installs authenticated state or navigates to the protected app.

The frontend source project is separate from this backend workspace; this report documents the implemented flow and its API contract.

## User flow

1. The user submits the existing login credentials.
2. If email MFA is required, the backend returns an `mfaChallenge` containing a non-empty `challengeId` and a masked destination, and sets a short-lived HttpOnly pre-authentication cookie. The client handles common response field naming variations, but never substitutes the username or email for the challenge ID.
3. The user explicitly requests a code. The frontend displays only the masked destination supplied by the backend.
4. The user enters the six-digit code. Only after verification succeeds does the frontend establish authenticated state and navigate into the app.
5. Canceling or reloading a pending challenge does not resume a session. The user signs in again.

## API contract

- Login uses the existing login endpoint and credentials.
- Code delivery calls `POST /api/mfa/send` with `{ "challengeId": "..." }`.
- Verification calls `POST /api/mfa/verify` with `{ "challengeId": "...", "otp": "123456" }`.
- Canceling an active challenge calls `POST /api/mfa/cancel` with `{ "challengeId": "..." }`.
- Requests include credentials so the browser sends the backend's pre-authentication cookie.
- If login indicates MFA is required but does not provide a usable challenge ID, the frontend stops with a clear error. It does not send an invalid request or fall back to password-only authentication.
- Verification returns the authenticated session data. The frontend activates authentication only after this response succeeds.

## Security behavior

- Pending MFA state is held in React memory and is not written to local or session storage.
- The frontend does not install or persist authenticated access or refresh tokens before MFA verification succeeds.
- The HttpOnly pre-authentication cookie binds code delivery, verification, and cancellation to the issued challenge.
- The email flow uses the backend-provided masked destination; the frontend does not expose or infer the full email address.
- Reloading clears the in-memory pending state. The user must restart login rather than resume an outstanding challenge.
- Client-side code length and form validation are usability checks, not security controls.

## Backend responsibilities

The backend must issue and bind a short-lived challenge, deliver and validate the OTP, expire and atomically consume it, enforce send and attempt limits, and invalidate a pending challenge after successful verification or cancellation. Cookie attributes and server-side checks are security controls; frontend behavior does not replace them.

Email MFA is the only supported MFA flow. Firebase phone/reCAPTCHA and SMS fallback have been removed.

## Validation

Run the frontend project's build and lint checks after changes:

```text
npm run build
npm run lint
```

These commands were not run from this backend workspace. End-to-end verification should additionally cover challenge replay, challenge and code expiry, throttling, session fixation, cookie attributes, cancellation, and account-enumeration behavior against the deployed backend.
