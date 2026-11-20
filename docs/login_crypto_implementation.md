# Secure Client-Side Login Encryption Implementation Blueprint

This report outlines the technical blueprint and step-by-step implementation plan for encrypting the application's login flow directly inside the React (Vite) frontend before payloads hit the browser network layer, alongside the corresponding decryption handlers within the Java Spring Boot backend.

---

## 1. Frontend Utility Configuration (`src/utils/cryptoUtil.js`)

This utility leverages the browser's native **Web Crypto API** to ensure zero-dependency payload wrapping. It generates an ephemeral, high-entropy 256-bit AES-GCM key, encrypts the credentials payload, and wraps the key using the server's public RSA key.

```javascript
// Production RSA Public Key (Safe to expose within frontend assets)
// Keep the matching private key server-side only; the frontend must use this public key.
const RSA_PUBLIC_KEY_PEM = `-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArId+/3sJnpk1whv4no40
rtsxaDRIEmg8wAUZWoYtO6ryHzr47FnAQZKZWGO+bqaZyFoOIkM2oONVBvPw09DM
B1FkKbLtOXyaKY6RO+S+cJYWTOKGgTnStO4vwqSYVHbSMxW90uwHENYxvDCtW+cC
yMkei8B2Odiy/eUGS0FnbnQqJfsf96WBtLrdFBBj5zZ8c6HZbUHemTB2ltLpmSPd
mq2gVnlh7qWkPHoH8Gyuvpt2ofU/f9x1/3ppSoiR60/5wjI4YCIINahQJLfkYoAK
S4OYypOmdg9g84Cmsh3A3v77/6nlfCgk6hEGHOELCYupRckQlUg/j+fTXkQNUWIS
7wIDAQAB
-----END PUBLIC KEY-----`;

function pemToArrayBuffer(pem) {
    const b64Lines = pem.replace(/-----BEGIN PUBLIC KEY-----|-----END PUBLIC KEY-----|\n|\r/g, '');
    const binaryStr = window.atob(b64Lines);
    const len = binaryStr.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
        bytes[i] = binaryStr.charCodeAt(i);
    }
    return bytes.buffer;
}

export async function encryptLoginPayload(username, password) {
    const encoder = new TextEncoder();
    const loginData = JSON.stringify({ username, password });

    // 1. Generate ephemeral 256-bit AES-GCM Key & 12-byte Initialization Vector (IV)
    const aesKey = await window.crypto.subtle.generateKey(
        { name: "AES-GCM", length: 256 },
        true,
        ["encrypt"]
    );
    const iv = window.crypto.getRandomValues(new Uint8Array(12));

    // 2. Encrypt structural credentials using the ephemeral AES key
    const encryptedDataBuffer = await window.crypto.subtle.encrypt(
        { name: "AES-GCM", iv: iv },
        aesKey,
        encoder.encode(loginData)
    );

    // 3. Export raw AES key buffer for RSA packaging
    const rawAesKey = await window.crypto.subtle.exportKey("raw", aesKey);

    // 4. Import the backend RSA Public Key context
    const rsaPublicKeyBuffer = pemToArrayBuffer(RSA_PUBLIC_KEY_PEM);
    const rsaKey = await window.crypto.subtle.importKey(
        "spki",
        rsaPublicKeyBuffer,
        { name: "RSA-OAEP", hash: "SHA-256" },
        false,
        ["encrypt"]
    );

    // 5. Encrypt raw AES key via RSA-OAEP
    const encryptedAesKeyBuffer = await window.crypto.subtle.encrypt(
        { name: "RSA-OAEP" },
        rsaKey,
        rawAesKey
    );

    // 6. Encode binary structures to Base64 strings for standard network JSON transit
    return {
        encryptedPayload: btoa(String.fromCharCode(...new Uint8Array(encryptedDataBuffer))),
        encryptedKey: btoa(String.fromCharCode(...new Uint8Array(encryptedAesKeyBuffer))),
        iv: btoa(String.fromCharCode(...iv))
    };
}
```

---

## 2. Frontend React Component Integration

```javascript
import { encryptLoginPayload } from '../utils/cryptoUtil';
import axios from 'axios';

const handleLogin = async (e) => {
    e.preventDefault();
    try {
        // Obfuscate structural credentials immediately upon user submission
        const securePayload = await encryptLoginPayload(username, password);

        // Forward strictly cryptographic payloads over the network
        const response = await axios.post('/api/login', securePayload);
        
        const token = response.data.token;
        localStorage.setItem('auth_token', token);
    } catch (error) {
        console.error("Authentication runtime exception", error);
    }
};
```

---

## 3. Backend Decryption Handler (`LoginDecryptionUtil.java`)

```java
package com.retail.app.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class LoginDecryptionUtil {

    public static String decryptLoginRequest(String encryptedPayloadB64, String encryptedKeyB64, String ivB64, String rsaPrivateKeyBase64) throws Exception {
        // 1. Reconstruct the RSA Private Key environment instance
        byte[] privateKeyBytes = Base64.getDecoder().decode(rsaPrivateKeyBase64.replace("\n", "").trim());
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(privateKeySpec);

        // 2. Decrypt the ephemeral AES Key container using RSA-OAEP SHA-256
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] rawAesKey = rsaCipher.doFinal(Base64.getDecoder().decode(encryptedKeyB64));

        // 3. Reconstruct the AES cipher configuration and Initialization Vector (IV)
        SecretKeySpec aesKeySpec = new SecretKeySpec(rawAesKey, "AES");
        byte[] ivBytes = Base64.getDecoder().decode(ivB64);
        byte[] encryptedPayloadBytes = Base64.getDecoder().decode(encryptedPayloadB64);

        // 4. Decrypt credentials payload using authenticated AES-GCM-128
        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, ivBytes);
        aesCipher.init(Cipher.DECRYPT_MODE, aesKeySpec, gcmSpec);

        byte[] decryptedBytes = aesCipher.doFinal(encryptedPayloadBytes);
        return new String(decryptedBytes);
    }
}
```

---

## 4. REST Controller Integration Pipeline

```java
package com.retail.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.app.dto.LoginRequest;
import com.retail.app.dto.SecureLoginRequest;
import com.retail.app.security.LoginDecryptionUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    @PostMapping("/api/login")
    public ResponseEntity<?> authenticate(@RequestBody SecureLoginRequest secureRequest) {
        try {
            String privateKeyEnv = System.getenv("RSA_PRIVATE_KEY_BASE64");
            
            // Decrypt the payload container safely within isolated server RAM
            String rawJson = LoginDecryptionUtil.decryptLoginRequest(
                secureRequest.getEncryptedPayload(),
                secureRequest.getEncryptedKey(),
                secureRequest.getIv(),
                privateKeyEnv
            );

            // Deserialize raw JSON structure back into your core verification model
            LoginRequest loginRequest = new ObjectMapper().readValue(rawJson, LoginRequest.class);
            
            // Route seamlessly into existing standard Spring Security pipelines...
            String jwtToken = authService.verifyAndGenerateJwt(loginRequest);
            return ResponseEntity.ok(new AuthResponse(jwtToken));
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid payload runtime structure.");
        }
    }
}
```

---

## 5. Production Operations and Rollout Notes

### 5.1 Backend Config Keys (Spring Boot)

The backend supports these runtime properties:

- `app.security.login.encrypted-only` (default `false`)
  - `false`: supports both plaintext and encrypted login payloads (safe rollout mode)
  - `true`: rejects plaintext login payloads and accepts encrypted payloads only
- `security.login.rsa-private-key-base64`
  - Base64-encoded PKCS#8 RSA private key used to decrypt the AES session key

Environment variable mapping used in this project:

- `APP_SECURITY_LOGIN_ENCRYPTED_ONLY`
- `APP_SECURITY_LOGIN_RSA_PRIVATE_KEY_BASE64`

### 5.2 Safe Rollout Sequence

1. Deploy backend with `app.security.login.encrypted-only=false` and valid RSA key.
2. Roll out frontend encrypted login client.
3. Monitor audit events (`LOGIN_CRYPTO` success/failure) and login failure rates.
4. After all active clients are upgraded, set `app.security.login.encrypted-only=true`.

This sequence keeps retailer login behavior stable during migration.

### 5.3 Key Rotation (Cloud-Friendly)

- Rotate key material using secret manager or environment update.
- Roll backend first with dual-mode still enabled (`encrypted-only=false`).
- Roll frontend with new public key.
- After cutover window, enforce encrypted-only if required.

### 5.4 Failure and Rollback

If encrypted logins fail in production:

1. Set `app.security.login.encrypted-only=false` to restore plaintext compatibility quickly.
2. Verify `security.login.rsa-private-key-base64` value and format.
3. Re-run encrypted login smoke tests and re-enable enforcement after validation.

### 5.5 Audit and Cost Controls

- Do not log decrypted credentials or encrypted payload content.
- Keep generic auth error responses (`401`) to avoid information leakage.
- Use existing per-endpoint rate limiting and stricter export throttling (`/api/audit/export`) to reduce cloud compute spikes.
