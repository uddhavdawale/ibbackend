package org.ib.ibbackend.controller;

import org.ib.ibbackend.service.WhatsAppWebhookIngestionService;
import org.ib.ibbackend.service.WhatsAppWebhookSignatureVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/whatsapp")
public class WhatsAppWebhookController {
    private static final int MAX_WEBHOOK_BYTES = 1_048_576;

    private final String appSecret;
    private final String verifyToken;
    private final WhatsAppWebhookIngestionService ingestionService;
    private final WhatsAppWebhookSignatureVerifier signatureVerifier;

    public WhatsAppWebhookController(
            @Value("${WHATSAPP_APP_SECRET:}") String appSecret,
            @Value("${WHATSAPP_WEBHOOK_VERIFY_TOKEN:}") String verifyToken,
            WhatsAppWebhookIngestionService ingestionService,
            WhatsAppWebhookSignatureVerifier signatureVerifier) {
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.verifyToken = verifyToken == null ? "" : verifyToken.trim();
        this.ingestionService = ingestionService;
        this.signatureVerifier = signatureVerifier;
    }

    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String providedToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if (!StringUtils.hasText(verifyToken)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "WhatsApp webhook verification is not configured");
        }
        if (!"subscribe".equals(mode) || !StringUtils.hasText(challenge)
                || !constantTimeEquals(verifyToken, providedToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("");
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receiveWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!StringUtils.hasText(appSecret)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "WhatsApp webhook signature verification is not configured");
        }
        if (rawBody.length > MAX_WEBHOOK_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "WhatsApp webhook payload is too large");
        }
        if (rawBody.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "WhatsApp webhook payload is required");
        }
        if (!signatureVerifier.isValid(rawBody, signature, appSecret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid WhatsApp webhook signature");
        }

        int acceptedEvents = ingestionService.accept(rawBody);
        return ResponseEntity.ok(Map.of("received", true, "acceptedEvents", acceptedEvents));
    }

    private boolean constantTimeEquals(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
