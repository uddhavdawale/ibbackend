# Mail-based MFA Implementation Report

## Overview
This backend now supports a config-driven email OTP (MFA) flow for login. When the feature is enabled, users must complete an email verification step after entering their password before the system issues JWT tokens.

## Status
- Implemented: Yes
- Default behavior: Password-only login remains active unless MFA is enabled
- Delivery mechanism: Email OTP via SMTP using Spring Mail

---

## Architecture

### 1. Configuration-driven behavior
The main toggle is controlled through application properties:

```properties
app.mfa.mail.enabled=${APP_MFA_MAIL_ENABLED:true}
app.mfa.mail.otp-length=${APP_MFA_MAIL_OTP_LENGTH:6}
app.mfa.mail.expiry-minutes=${APP_MFA_MAIL_EXPIRY_MINUTES:5}
app.mfa.mail.subject=${APP_MFA_MAIL_SUBJECT:Your verification code}
app.mfa.mail.from=${APP_MFA_MAIL_FROM:noreply@localhost}
```

The binding class is:
- `MailMfaProperties`

This design allows MFA to be enabled or disabled without code changes.

### 2. OTP generation and validation
The service responsible for issuing and validating OTPs is:
- `MailMfaService`

Responsibilities:
- Generate a numeric OTP of configured length
- Store it with expiry timestamp in memory
- Send the OTP to the user email when SMTP is configured
- Verify the submitted code within expiry window
- Remove the OTP after successful validation

### 3. Login flow update
The login controller now checks MFA status after password validation:
- If MFA is disabled: normal JWT issuance continues as before
- If MFA is enabled: the backend sends the OTP to the user email and responds with a challenge instead of issuing tokens

### 4. MFA API endpoints
Added endpoints:
- `POST /api/mfa/send`
- `POST /api/mfa/verify`

These endpoints use the username to resolve the user and email OTP to the associated address.

---

## Files added
- `src/main/java/org/ib/ibbackend/config/MailMfaProperties.java`
- `src/main/java/org/ib/ibbackend/service/MailMfaService.java`
- `src/main/java/org/ib/ibbackend/controller/MfaController.java`
- `src/main/java/org/ib/ibbackend/dto/MfaOtpRequest.java`

## Files updated
- `src/main/resources/application.properties`
- `src/main/java/org/ib/ibbackend/IbbackendApplication.java`
- `src/main/java/org/ib/ibbackend/security/SecurityConfig.java`
- `src/main/java/org/ib/ibbackend/controller/LoginController.java`
- `src/main/java/org/ib/ibbackend/dto/LoginResponse.java`
- `pom.xml`

---

## Security notes
- OTPs are stored in memory and are short-lived.
- Actual production deployments should prefer a centralized cache or Redis-backed store for OTP persistence.
- The app should use a real SMTP provider rather than localhost in production.
- OTPs should not be returned in the login API response body in a production UI; only a challenge status should be returned.

---

## Example request flow

### Step 1: login request
```http
POST /api/login
{
  "username": "owner@example.com",
  "password": "yourPassword"
}
```

### Step 2: backend challenge response when MFA enabled
```json
{
  "success": false,
  "message": "MFA verification required",
  "requiresMfa": true,
  "email": "owner@example.com",
  "user": {
    "id": 1,
    "username": "Owner Name",
    "name": "Owner Name",
    "email": "owner@example.com",
    "role": "STORE_ADMIN"
  }
}
```

### Step 3: send OTP
```http
POST /api/mfa/send
{
  "username": "owner@example.com"
}
```

### Step 4: verify OTP
```http
POST /api/mfa/verify
{
  "username": "owner@example.com",
  "otp": "123456"
}
```

### Step 5: success response
```json
{
  "success": true,
  "message": "Login successful",
  "accessToken": "...",
  "refreshToken": "...",
  "tokenType": "Bearer",
  "user": {
    "id": 1,
    "username": "Owner Name",
    "name": "Owner Name",
    "email": "owner@example.com",
    "role": "STORE_ADMIN"
  }
}
```

---

## Validation
The implemented OTP logic was validated with a focused test suite:
- `MailMfaServiceTest`

Evidence from the latest run:
- Tests run: 2
- Failures: 0
- Errors: 0
- Skipped: 0

---

## Recommended production follow-up
1. Move OTP storage to Redis or database-backed cache.
2. Add optional rate limiting on `/api/mfa/send` to prevent abuse.
3. Add resend-timer UI logic in the frontend.
4. Log MFA events for security auditing.
5. Add expired / invalid OTP error handling in the UI.
