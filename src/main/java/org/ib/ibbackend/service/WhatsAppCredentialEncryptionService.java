package org.ib.ibbackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class WhatsAppCredentialEncryptionService {
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String base64EncryptionKey;

    public WhatsAppCredentialEncryptionService(
            @Value("${WHATSAPP_CREDENTIAL_ENCRYPTION_KEY:}") String base64EncryptionKey) {
        this.base64EncryptionKey = base64EncryptionKey == null ? "" : base64EncryptionKey.trim();
    }

    public boolean isConfigured() {
        return StringUtils.hasText(base64EncryptionKey);
    }

    public String encrypt(int tenantId, String plaintext) {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(tenantContext(tenantId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not encrypt WhatsApp credentials", ex);
        }
    }

    public String decrypt(int tenantId, String encryptedValue) {
        byte[] encrypted;
        try {
            encrypted = Base64.getDecoder().decode(encryptedValue);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Stored WhatsApp credentials are invalid", ex);
        }
        if (encrypted.length <= NONCE_LENGTH) {
            throw new IllegalStateException("Stored WhatsApp credentials are invalid");
        }

        ByteBuffer buffer = ByteBuffer.wrap(encrypted);
        byte[] nonce = new byte[NONCE_LENGTH];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(tenantContext(tenantId));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not decrypt WhatsApp credentials", ex);
        }
    }

    private SecretKeySpec encryptionKey() {
        try {
            byte[] key = Base64.getDecoder().decode(base64EncryptionKey);
            if (key.length != 32) {
                throw new IllegalArgumentException("Expected a 256-bit key");
            }
            return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("WhatsApp credential encryption is not configured", ex);
        }
    }

    private byte[] tenantContext(int tenantId) {
        return ("whatsapp-tenant:" + tenantId).getBytes(StandardCharsets.UTF_8);
    }
}
