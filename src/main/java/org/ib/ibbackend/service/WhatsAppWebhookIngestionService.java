package org.ib.ibbackend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ib.ibbackend.repository.WhatsAppIntegrationRepository.IntegrationSettings;
import org.ib.ibbackend.repository.WhatsAppWebhookEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@Service
public class WhatsAppWebhookIngestionService {
    private final ObjectMapper objectMapper;
    private final WhatsAppIntegrationService integrationService;
    private final WhatsAppWebhookEventRepository eventRepository;

    public WhatsAppWebhookIngestionService(ObjectMapper objectMapper,
                                           WhatsAppIntegrationService integrationService,
                                           WhatsAppWebhookEventRepository eventRepository) {
        this.objectMapper = objectMapper;
        this.integrationService = integrationService;
        this.eventRepository = eventRepository;
    }

    @Transactional
    public int accept(byte[] rawBody) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid WhatsApp webhook payload");
        }
        if (payload == null || !payload.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid WhatsApp webhook payload");
        }

        int accepted = 0;
        JsonNode entries = payload.path("entry");
        if (!entries.isArray()) {
            return accepted;
        }

        for (JsonNode entry : entries) {
            JsonNode changes = entry.path("changes");
            if (!changes.isArray()) {
                continue;
            }
            for (JsonNode change : changes) {
                JsonNode value = change.path("value");
                String phoneNumberId = value.path("metadata").path("phone_number_id").asText("");
                if (phoneNumberId.isBlank()) {
                    continue;
                }

                IntegrationSettings integration = integrationService
                        .findEnabledByPhoneNumberId(phoneNumberId)
                        .orElse(null);
                if (integration == null) {
                    continue;
                }

                accepted += insertMessages(value.path("messages"), integration);
                accepted += insertStatuses(value.path("statuses"), integration);
            }
        }
        return accepted;
    }

    private int insertMessages(JsonNode messages, IntegrationSettings integration) {
        if (!messages.isArray()) {
            return 0;
        }
        int inserted = 0;
        for (JsonNode message : messages) {
            String providerMessageId = message.path("id").asText("");
            if (providerMessageId.isBlank()) {
                continue;
            }
            inserted += eventRepository.insert(
                    integration.tenantId(),
                    integration.phoneNumberId(),
                    providerMessageId,
                    "MESSAGE",
                    toJson(message));
        }
        return inserted;
    }

    private int insertStatuses(JsonNode statuses, IntegrationSettings integration) {
        if (!statuses.isArray()) {
            return 0;
        }
        int inserted = 0;
        for (JsonNode status : statuses) {
            String messageId = status.path("id").asText("");
            String statusName = status.path("status").asText("");
            String timestamp = status.path("timestamp").asText("");
            if (messageId.isBlank() || statusName.isBlank() || timestamp.isBlank()) {
                continue;
            }
            String providerEventId = "status:" + messageId + ":" + statusName + ":" + timestamp;
            inserted += eventRepository.insert(
                    integration.tenantId(),
                    integration.phoneNumberId(),
                    providerEventId,
                    "DELIVERY_STATUS",
                    toJson(status));
        }
        return inserted;
    }

    private String toJson(JsonNode event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid WhatsApp webhook payload");
        }
    }
}
