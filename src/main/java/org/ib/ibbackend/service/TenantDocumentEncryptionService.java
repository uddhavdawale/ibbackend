package org.ib.ibbackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.ib.ibbackend.exception.DocumentStorageNotConfiguredException;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class TenantDocumentEncryptionService {
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String base64EncryptionKey;

    public TenantDocumentEncryptionService(
            @Value("${app.tenant-documents.encryption-key:}") String base64EncryptionKey) {
        this.base64EncryptionKey = base64EncryptionKey;
    }

    public byte[] encrypt(byte[] content) {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] encryptedContent = cipher.doFinal(content);
            return ByteBuffer.allocate(nonce.length + encryptedContent.length)
                    .put(nonce)
                    .put(encryptedContent)
                    .array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not encrypt tenant document", ex);
        }
    }

    public byte[] decrypt(byte[] encryptedContent) {
        if (encryptedContent == null || encryptedContent.length <= NONCE_LENGTH) {
            throw new IllegalStateException("Stored tenant document is invalid");
        }

        ByteBuffer buffer = ByteBuffer.wrap(encryptedContent);
        byte[] nonce = new byte[NONCE_LENGTH];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not decrypt tenant document", ex);
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
            throw new DocumentStorageNotConfiguredException();
        }
    }
}
