package org.ib.ibbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class WhatsAppCloudApiClient {
    private static final Pattern GRAPH_VERSION_PATTERN = Pattern.compile("^v[0-9]+\\.[0-9]+$");

    private final RestClient restClient;
    private final String graphApiVersion;

    public WhatsAppCloudApiClient(RestClient.Builder restClientBuilder,
                                  @Value("${app.whatsapp.graph-api-version:v23.0}") String graphApiVersion) {
        if (!GRAPH_VERSION_PATTERN.matcher(graphApiVersion).matches()) {
            throw new IllegalArgumentException("Invalid WhatsApp Graph API version");
        }
        this.graphApiVersion = graphApiVersion;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = restClientBuilder
                .baseUrl("https://graph.facebook.com")
                .requestFactory(requestFactory)
                .build();
    }

    public void sendText(String phoneNumberId, String accessToken, String recipient, String message) {
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", recipient,
                "type", "text",
                "text", Map.of("preview_url", false, "body", message));

        restClient.post()
                .uri("/{version}/{phoneNumberId}/messages", graphApiVersion, phoneNumberId)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    public void validateCredentials(String phoneNumberId, String accessToken) {
        JsonNode response = restClient.get()
                .uri("/{version}/{phoneNumberId}?fields=id,display_phone_number",
                        graphApiVersion, phoneNumberId)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !phoneNumberId.equals(response.path("id").asText())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Meta did not verify the configured WhatsApp phone number ID");
        }
    }
}
