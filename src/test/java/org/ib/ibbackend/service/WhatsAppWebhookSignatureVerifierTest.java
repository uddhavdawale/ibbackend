package org.ib.ibbackend.service;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppWebhookSignatureVerifierTest {
    private final WhatsAppWebhookSignatureVerifier verifier = new WhatsAppWebhookSignatureVerifier();

    @Test
    void acceptsValidMetaSignatureForExactRawBody() throws Exception {
        byte[] body = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "test-app-secret";
        String signature = signature(body, secret);

        assertThat(verifier.isValid(body, signature, secret)).isTrue();
    }

    @Test
    void rejectsModifiedBodyAndMalformedSignature() throws Exception {
        byte[] body = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);
        String signature = signature(body, "test-app-secret");

        assertThat(verifier.isValid(
                "{\"object\":\"changed\"}".getBytes(StandardCharsets.UTF_8),
                signature,
                "test-app-secret")).isFalse();
        assertThat(verifier.isValid(body, "sha256=invalid", "test-app-secret")).isFalse();
    }

    private String signature(byte[] body, String secret) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(hmac.doFinal(body));
    }
}
