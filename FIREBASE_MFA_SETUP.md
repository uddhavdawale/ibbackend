# Firebase OTP MFA Setup

## 1) Backend environment variables
Set these in your runtime environment:

```bash
APP_MFA_FIREBASE_ENABLED=true
APP_MFA_FIREBASE_PROJECT_ID=<your-firebase-project-id>
APP_MFA_FIREBASE_REQUIRED_EMAIL_VERIFIED=false

# Choose one credentials mode:
APP_MFA_FIREBASE_CREDENTIALS_PATH=/path/to/service-account.json
# OR
APP_MFA_FIREBASE_CREDENTIALS_JSON_BASE64=<base64-of-service-account-json>
```

Keep mail MFA as fallback if needed:

```bash
APP_MFA_MAIL_ENABLED=true
```

## 2) API flow
- Call `POST /api/login` with username/password.
- If credentials are valid and MFA is enabled, response returns `requiresMfa=true`.
- Frontend completes Firebase phone OTP and obtains Firebase ID token.
- Call `POST /api/mfa/verify` with:

```json
{
  "username": "owner@example.com",
  "firebaseIdToken": "<firebase-id-token>"
}
```

If Firebase is disabled, mail OTP still works with:

```json
{
  "username": "owner@example.com",
  "otp": "123456"
}
```

## 3) Security notes
- Never send raw OTP code to backend when using Firebase OTP; send only Firebase ID token.
- Store Firebase credentials in secret manager, not in source control.
- Rotate service account keys and JWT secrets periodically.

