package org.ib.ibbackend.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class WhatsAppWebhookSignatureVerifier {
    public boolean isValid(byte[] rawBody, String signatureHeader, String appSecret) {
        if (rawBody == null || signatureHeader == null || appSecret == null
                || signatureHeader.length() != 71 || !signatureHeader.startsWith("sha256=")) {
            return false;
        }

        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(hmac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    signatureHeader.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Could not verify WhatsApp webhook signature", ex);
        }
    }
}
