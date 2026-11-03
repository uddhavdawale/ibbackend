package org.ib.ibbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ib.ibbackend.dto.LoginRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class LoginCryptoService {
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int EXPECTED_GCM_IV_BYTES = 12;
    private static final int MAX_ENCRYPTED_PAYLOAD_BYTES = 16 * 1024;
    private static final int MAX_ENCRYPTED_KEY_BYTES = 1024;
    private static final int MAX_IV_BYTES = 64;
    private static final String DEFAULT_PRIVATE_KEY_RESOURCE = "private_key.pem";

    private final ObjectMapper objectMapper;
    private final String configuredPrivateKeyBase64;

    private volatile PrivateKey cachedPrivateKey;
    private volatile String cachedPrivateKeyMaterial;

    private static final Logger log = LoggerFactory.getLogger(LoginCryptoService.class);

    public LoginCryptoService() {
        this(new ObjectMapper(), "");
    }

    @Autowired
    public LoginCryptoService(
            ObjectMapper objectMapper,
            @Value("${security.login.rsa-private-key-base64:}") String configuredPrivateKeyBase64
    ) {
        this.objectMapper = objectMapper;
        this.configuredPrivateKeyBase64 = configuredPrivateKeyBase64;
    }

    public LoginRequest resolveLoginRequest(LoginRequest request) {
        if (request == null) {
            throw new InvalidLoginCryptoPayloadException("Login request is required");
        }

        if (!isEncryptedPayload(request)) {
            return request;
        }

        log.debug("Encrypted login payload detected; starting decrypt flow");

        if (isBlank(request.getEncryptedPayload()) || isBlank(request.getEncryptedKey()) || isBlank(request.getIv())) {
            throw new InvalidLoginCryptoPayloadException("Encrypted login payload is incomplete");
        }

        try {
            String decryptedJson = decrypt(
                    request.getEncryptedPayload(),
                    request.getEncryptedKey(),
                    request.getIv()
            );

            LoginRequest decryptedRequest = objectMapper.readValue(decryptedJson, LoginRequest.class);
            if (isBlank(decryptedRequest.getPassword())) {
                throw new InvalidLoginCryptoPayloadException("Decrypted password is missing");
            }

            if (isBlank(decryptedRequest.getUsername()) && isBlank(decryptedRequest.getEmail())) {
                throw new InvalidLoginCryptoPayloadException("Decrypted login identifier is missing");
            }

            String identifierType = !isBlank(decryptedRequest.getUsername()) ? "username" : "email";
            log.debug("Encrypted login payload decrypted successfully; identifierType={}", identifierType);
            return decryptedRequest;
        } catch (InvalidLoginCryptoPayloadException ex) {
            log.error("Encrypted login payload rejected: {}", ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            log.error("Encrypted login payload decryption failed with unexpected error type: {}", ex.getClass().getSimpleName(), ex);
            throw new InvalidLoginCryptoPayloadException("Unable to decrypt login payload", ex);
        }
    }

    public boolean isEncryptedPayload(LoginRequest request) {
        if (request == null) {
            return false;
        }
        return !isBlank(request.getEncryptedPayload())
                || !isBlank(request.getEncryptedKey())
                || !isBlank(request.getIv());
    }

    private String decrypt(String encryptedPayloadB64, String encryptedKeyB64, String ivB64) throws GeneralSecurityException {
        byte[] encryptedPayloadBytes = decodeBase64(encryptedPayloadB64, MAX_ENCRYPTED_PAYLOAD_BYTES, "payload");
        byte[] encryptedKeyBytes = decodeBase64(encryptedKeyB64, MAX_ENCRYPTED_KEY_BYTES, "key");
        byte[] ivBytes = decodeBase64(ivB64, MAX_IV_BYTES, "iv");

        log.debug(
                "Decrypting encrypted login payload; payloadBytes={}, encryptedKeyBytes={}, ivBytes={}",
                encryptedPayloadBytes.length,
                encryptedKeyBytes.length,
                ivBytes.length
        );

        if (ivBytes.length != EXPECTED_GCM_IV_BYTES) {
            throw new InvalidLoginCryptoPayloadException("Invalid IV size");
        }

        byte[] rawAesKey = decryptAesKey(encryptedKeyBytes, resolvePrivateKey());

        if (rawAesKey.length != 16 && rawAesKey.length != 24 && rawAesKey.length != 32) {
            throw new InvalidLoginCryptoPayloadException("Invalid AES key size");
        }

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivBytes);
        aesCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(rawAesKey, "AES"), gcmSpec);

        byte[] decryptedBytes = aesCipher.doFinal(encryptedPayloadBytes);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
    private byte[] decryptAesKey(byte[] encryptedKeyBytes, PrivateKey privateKey) {
        try {
            // 1. Initialize standard RSA/ECB/OAEPPadding instance context
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPPadding");

            // 2. Explicitly mandate SHA-256 for BOTH the main digest and MGF1 parameters
            OAEPParameterSpec oaepSpec = new OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT
            );

            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec);
            byte[] decrypted = rsaCipher.doFinal(encryptedKeyBytes);
            log.info("SUCCESS: RSA Asymmetric Unwrap completed clean using synchronized SHA-256 configuration.");
            return decrypted;

        } catch (GeneralSecurityException ex) {
            log.error("Primary synchronized RSA-OAEP SHA-256 decrypt failed. Attempting legacy fallback cascade logic...", ex);

            // Keep your original cascading try/catch blocks below for compatibility if needed...
            try {
                Cipher rsaCipherFallback = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
                rsaCipherFallback.init(Cipher.DECRYPT_MODE, privateKey);
                return rsaCipherFallback.doFinal(encryptedKeyBytes);
            } catch (GeneralSecurityException sha1Ex) {
                try {
                    Cipher rsaCipherPkcs1 = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    rsaCipherPkcs1.init(Cipher.DECRYPT_MODE, privateKey);
                    return rsaCipherPkcs1.doFinal(encryptedKeyBytes);
                } catch (GeneralSecurityException pkcs1Ex) {
                    throw new InvalidLoginCryptoPayloadException(
                            "Unable to decrypt encrypted key. Check frontend public key pair and RSA mode/padding",
                            pkcs1Ex
                    );
                }
            }
        }
    }
    private byte[] decryptAesKey_failed(byte[] encryptedKeyBytes, PrivateKey privateKey) {
        try {
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
            return rsaCipher.doFinal(encryptedKeyBytes);
        } catch (GeneralSecurityException ex) {
            log.debug("RSA-OAEP SHA-256 decrypt failed; trying SHA-1 compatibility fallback");
            try {
                Cipher rsaCipherFallback = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
                rsaCipherFallback.init(Cipher.DECRYPT_MODE, privateKey);
                byte[] decrypted = rsaCipherFallback.doFinal(encryptedKeyBytes);
                log.warn("Encrypted login used legacy RSA OAEP SHA-1 compatibility mode");
                return decrypted;
            } catch (GeneralSecurityException sha1Ex) {
                log.debug("RSA-OAEP SHA-1 decrypt failed; trying PKCS#1 v1.5 compatibility fallback");
                try {
                    Cipher rsaCipherPkcs1 = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    rsaCipherPkcs1.init(Cipher.DECRYPT_MODE, privateKey);
                    byte[] decrypted = rsaCipherPkcs1.doFinal(encryptedKeyBytes);
                    log.warn("Encrypted login used legacy RSA PKCS1 v1.5 compatibility mode");
                    return decrypted;
                } catch (GeneralSecurityException pkcs1Ex) {
                    throw new InvalidLoginCryptoPayloadException(
                            "Unable to decrypt encrypted key. Check frontend public key pair and RSA mode/padding",
                            pkcs1Ex
                    );
                }
            }
        }
    }

    private PrivateKey resolvePrivateKey() {
        List<KeySourceCandidate> candidates = resolveConfiguredKeyCandidates();
        if (candidates.isEmpty()) {
            log.error("Encrypted login is enabled but RSA private key material is not configured");
            throw new InvalidLoginCryptoPayloadException("Encrypted login is not configured");
        }

        for (KeySourceCandidate candidate : candidates) {
            String normalizedMaterial = normalizePrivateKeyMaterial(candidate.keyMaterial());
            if (normalizedMaterial.isBlank() && (candidate.keyMaterial() == null || candidate.keyMaterial().isBlank())) {
                continue;
            }

            PrivateKey existing = cachedPrivateKey;
            if (existing != null && normalizedMaterial.equals(cachedPrivateKeyMaterial)) {
                log.debug("Using cached RSA private key for encrypted login decrypt; source={}", candidate.source());
                return existing;
            }

            try {
                PrivateKey rebuilt = buildPrivateKey(candidate.keyMaterial());

                cachedPrivateKey = rebuilt;
                cachedPrivateKeyMaterial = normalizePrivateKeyMaterial(candidate.keyMaterial());
                log.debug("RSA private key material loaded and cached for encrypted login decrypt; source={}", candidate.source());
                return rebuilt;
            } catch (Exception ex) {
                log.error("Invalid RSA private key configuration from source={}", candidate.source(), ex);
            }
        }

        throw new InvalidLoginCryptoPayloadException("Invalid RSA private key configuration");
    }

    private PrivateKey buildPrivateKey(String keyMaterial) throws Exception {
        // Variant 1/2: raw PEM or PKCS8 DER base64.
        try {
            byte[] privateKeyBytes = Base64.getDecoder().decode(normalizePrivateKeyMaterial(keyMaterial));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        } catch (Exception ignored) {
            // Variant 3: value is base64-encoded PEM text.
            byte[] decodedOnce = Base64.getDecoder().decode(keyMaterial == null ? "" : keyMaterial.trim());
            String pemText = new String(decodedOnce, StandardCharsets.UTF_8);
            byte[] privateKeyBytes = Base64.getDecoder().decode(normalizePrivateKeyMaterial(pemText));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        }
    }

    private List<KeySourceCandidate> resolveConfiguredKeyCandidates() {
        List<KeySourceCandidate> candidates = new ArrayList<>();

        if (!isBlank(configuredPrivateKeyBase64)) {
            candidates.add(new KeySourceCandidate("property:security.login.rsa-private-key-base64", configuredPrivateKeyBase64));
        }

        String envValue = System.getenv("RSA_PRIVATE_KEY_BASE64");
        if (!isBlank(envValue)) {
            candidates.add(new KeySourceCandidate("env:RSA_PRIVATE_KEY_BASE64", envValue));
        }

        String classpathKey = loadPrivateKeyFromClasspath();
        if (!isBlank(classpathKey)) {
            candidates.add(new KeySourceCandidate("classpath:" + DEFAULT_PRIVATE_KEY_RESOURCE, classpathKey));
        }

        return candidates;
    }


    private String loadPrivateKeyFromClasspath() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = LoginCryptoService.class.getClassLoader();
        }

        if (classLoader == null) {
            return "";
        }

        try (InputStream inputStream = classLoader.getResourceAsStream(DEFAULT_PRIVATE_KEY_RESOURCE)) {
            if (inputStream == null) {
                return "";
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new InvalidLoginCryptoPayloadException("Unable to load RSA private key resource", ex);
        }
    }

    private String normalizePrivateKeyMaterial(String rawKey) {
        if (rawKey == null) {
            return "";
        }

        return rawKey
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim();
    }

    private byte[] decodeBase64(String value, int maxBytes, String fieldName) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0 || decoded.length > maxBytes) {
                throw new InvalidLoginCryptoPayloadException("Invalid encrypted " + fieldName + " size");
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new InvalidLoginCryptoPayloadException("Invalid base64 content for encrypted " + fieldName, ex);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record KeySourceCandidate(String source, String keyMaterial) {
    }

    public static final class InvalidLoginCryptoPayloadException extends RuntimeException {
        public InvalidLoginCryptoPayloadException(String message) {
            super(message);
        }

        public InvalidLoginCryptoPayloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
