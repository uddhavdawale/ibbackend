package org.ib.ibbackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

@Service
public class BrevoEmailService {
    private static final String SEND_EMAIL_PATH = "/smtp/email";

    private final String apiKey;
    private final RestClient restClient;

    public BrevoEmailService(@Value("${app.mail.brevo.api-key:}") String apiKey,
                             RestClient.Builder restClientBuilder) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.restClient = restClientBuilder
                .baseUrl("https://api.brevo.com/v3")
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public void send(String sender, String recipient, String subject, String textContent) {
        if (!isConfigured()) {
            throw new IllegalStateException("Brevo API key is not configured");
        }

        Map<String, Object> payload = Map.of(
                "sender", Map.of("email", sender),
                "to", List.of(Map.of("email", recipient)),
                "subject", subject,
                "textContent", textContent
        );
        try {
            restClient.post()
                    .uri(SEND_EMAIL_PATH)
                    .header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new BrevoDeliveryException(ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw new BrevoDeliveryException(null, ex);
        }
    }

    public static final class BrevoDeliveryException extends RuntimeException {
        private final Integer statusCode;

        private BrevoDeliveryException(Integer statusCode, Exception cause) {
            super("Brevo email delivery failed", cause);
            this.statusCode = statusCode;
        }

        public Integer getStatusCode() {
            return statusCode;
        }
    }
}
