# Password Reset Frontend Integration Report

Date: 2026-11-14
Backend: ibbackend

## Issue and backend change

Password reset emails previously linked to `/login#resetToken=...`, which opens
the login page instead of a dedicated password-reset screen. Emails now link to:

`{APP_FRONTEND_BASE_URL}/reset-password#resetToken={URL-encoded-token}`

Configure `APP_FRONTEND_BASE_URL` as the deployed frontend origin. The token is
in the URL fragment rather than the query string; browsers do not send fragments
to the frontend web server in HTTP requests.

## Frontend work required

1. Register a directly loadable `/reset-password` route. It must not redirect
   unauthenticated visitors to `/login`; password-reset token possession is the
   authorization for this flow.
2. On page load, read `resetToken` from `window.location.hash` and show a
   new-password and confirm-password form. If the fragment is absent, malformed,
   or the token is rejected, show a clear expired/invalid-link state and offer a
   link back to the existing forgot-password form.
3. Submit the new password (not a client-side hash) to
   `POST /api/auth/password/reset` with JSON:

   ```json
   {
     "token": "<resetToken>",
     "newPassword": "<user-entered-password>"
   }
   ```

4. On `204 No Content`, show a success message and let the user return to login.
   On `400 Bad Request`, retain the form and explain that the link may have
   expired or already been used, or that the password does not meet policy.
   Require at least 12 characters in the UI; the backend also enforces policy.
5. After extracting the token, remove it from the visible URL using
   `history.replaceState` and keep it only in page memory. Do not put it in
   local storage, analytics, application logs, or third-party requests. Avoid
   third-party assets on this page and set a restrictive referrer policy.
6. Preserve the existing forgot-password UX. Submit the email to
   `POST /api/auth/password/forgot`; the backend always returns `202 Accepted`
   with a generic message, whether or not an account exists. Show that same
   confirmation to avoid account enumeration.

## Backend API and security behavior

- Both password recovery endpoints are public; a signed-in session is not
  required.
- `POST /api/auth/password/forgot` accepts `{"email":"..."}`. Requests are
  rate-limited and recently issued links are throttled.
- Reset tokens are random, expire (30 minutes by default, configurable), are
  stored server-side only as digests, and can be used once.
- A successful reset updates the password and revokes existing refresh sessions.
- Invalid, expired, and consumed tokens receive the same generic `400` response.
- Configure production frontend origin and HTTPS via `APP_FRONTEND_BASE_URL`;
  do not use the development localhost default in production.

## Verification checklist

- Open a received email link in a logged-out browser and confirm the
  `/reset-password` screen is shown.
- Complete reset with a valid token, then confirm the old password no longer
  works and the new password does.
- Confirm reuse and expiration show the invalid-link state without redirecting
  to login.
- Confirm the browser URL no longer displays the token after the page reads it.
