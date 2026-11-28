package org.ib.ibbackend.controller;

import org.ib.ibbackend.service.WhatsAppWebhookIngestionService;
import org.ib.ibbackend.service.WhatsAppWebhookSignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WhatsAppWebhookControllerTest {
    private final WhatsAppWebhookIngestionService ingestionService = mock(WhatsAppWebhookIngestionService.class);
    private final WhatsAppWebhookController controller = new WhatsAppWebhookController(
            "app-secret", "verify-token", ingestionService, new WhatsAppWebhookSignatureVerifier());

    @Test
    void answersMetaVerificationOnlyForTheConfiguredToken() {
        ResponseEntity<String> verified = controller.verifyWebhook("subscribe", "verify-token", "challenge");
        ResponseEntity<String> rejected = controller.verifyWebhook("subscribe", "wrong-token", "challenge");

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).isEqualTo("challenge");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsInvalidSignaturesBeforePersistingAnyEvent() {
        byte[] body = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> controller.receiveWebhook(body, "sha256=invalid"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(ingestionService);
    }

    @Test
    void persistsOnlyAfterVerifyingTheRawBodySignature() throws Exception {
        byte[] body = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);
        when(ingestionService.accept(body)).thenReturn(1);

        ResponseEntity<Map<String, Object>> response = controller.receiveWebhook(body, signature(body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("received", true).containsEntry("acceptedEvents", 1);
        verify(ingestionService).accept(body);
    }

    private String signature(byte[] body) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec("app-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(hmac.doFinal(body));
    }
}
