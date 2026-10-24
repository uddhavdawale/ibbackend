# Frontend MFA Implementation Report

## Goal
Add email-based MFA flow to the frontend so users can complete a second-factor step after password validation before entering the authenticated dashboard.

---

## Expected UX flow

### 1. Login screen
The frontend login form should keep sending the existing username and password to:

```http
POST /api/login
```

If MFA is enabled, the backend will respond with a challenge instead of issuing tokens.

Expected response shape:

```json
{
  "success": false,
  "message": "MFA verification required",
  "requiresMfa": true,
  "email": "user@example.com",
  "user": {
    "id": 1,
    "username": "Owner Name",
    "name": "Owner Name",
    "email": "user@example.com",
    "role": "STORE_ADMIN"
  }
}
```

### 2. MFA challenge screen
Once the app detects `requiresMfa === true`, the UI should:
- show a verification card
- display the masked user email, for example: `u***@example.com`
- ask the user for the 6-digit OTP
- provide a resend action
- disable resend for a short cooldown (e.g. 30 seconds)

### 3. Send OTP request
When the MFA screen opens, call:

```http
POST /api/mfa/send
{
  "username": "user@example.com"
}
```

The backend sends the OTP to the registered email address.

### 4. OTP verification
When the user submits the 6-digit code:

```http
POST /api/mfa/verify
{
  "username": "user@example.com",
  "otp": "123456"
}
```

On success, the backend responds with the usual authenticated tokens and user payload.

Example success response:

```json
{
  "success": true,
  "message": "Login successful",
  "accessToken": "jwt.access.token",
  "refreshToken": "jwt.refresh.token",
  "tokenType": "Bearer",
  "user": {
    "id": 1,
    "username": "Owner Name",
    "name": "Owner Name",
    "email": "user@example.com",
    "role": "STORE_ADMIN"
  }
}
```

---

## State changes recommended in frontend

### Add a new auth step
Use an app state such as:

```ts
const [mfaRequired, setMfaRequired] = useState(false);
const [mfaEmail, setMfaEmail] = useState('');
const [otp, setOtp] = useState('');
const [isVerifyingOtp, setIsVerifyingOtp] = useState(false);
const [otpSent, setOtpSent] = useState(false);
```

### Login logic
After login response:
- if `response.requiresMfa` is true, stop normal auth and show MFA screen
- store the username/email for OTP verification
- do not store JWT tokens until verification completes

### Handle error states
Show errors for:
- invalid password
- user not found
- invalid or expired OTP
- MFA disabled on backend
- resend rate limit

---

## Recommended UI components
- LoginForm
- MfaVerificationCard
- ResendOtpButton
- FormErrorBanner
- LoadingSpinner / Button loading state

---

## Important client-side notes
1. Do not treat `requiresMfa` as a normal success state.
2. Keep the JWT tokens in memory only after `/api/mfa/verify` succeeds.
3. On OTP verify failure, clear token state and keep the user on the MFA screen.
4. Keep email input masked before display.
5. Add a count-down timer for resend to prevent spam.

---

## Suggested frontend flow example

```ts
const handleLogin = async () => {
  const res = await api.post('/api/login', { username, password });

  if (res.data.requiresMfa) {
    setMfaRequired(true);
    setMfaEmail(res.data.email);
    await sendMfaOtp(res.data.email);
    return;
  }

  saveAuth(res.data);
  navigate('/dashboard');
};

const sendMfaOtp = async (email: string) => {
  await api.post('/api/mfa/send', { username: email });
};

const verifyMfaOtp = async () => {
  const res = await api.post('/api/mfa/verify', {
    username: mfaEmail,
    otp,
  });

  saveAuth(res.data);
  navigate('/dashboard');
};
```

---

## Backend contract summary
The frontend should be prepared for these endpoints:

- `POST /api/login`
- `POST /api/mfa/send`
- `POST /api/mfa/verify`

The backend is designed to keep the old login as a fallback when `APP_MFA_MAIL_ENABLED=false`.

---

## Final recommendation
The frontend should implement the MFA step as a two-phase login experience:
1. password login challenge
2. email OTP challenge

This preserves user experience when MFA is off and enables secure second-factor authentication when the backend config enables it.
