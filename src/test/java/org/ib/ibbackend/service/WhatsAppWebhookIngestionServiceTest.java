package org.ib.ibbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ib.ibbackend.repository.WhatsAppIntegrationRepository.IntegrationSettings;
import org.ib.ibbackend.repository.WhatsAppWebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookIngestionServiceTest {
    @Mock
    private WhatsAppIntegrationService integrationService;

    @Mock
    private WhatsAppWebhookEventRepository eventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void routesMessagesToTheTenantMappedFromTheMetaPhoneNumberId() {
        when(integrationService.findEnabledByPhoneNumberId("123456789"))
                .thenReturn(Optional.of(new IntegrationSettings(
                        27, "123456789", "987654321", "+919876543210", "encrypted", true)));
        when(eventRepository.insert(anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        WhatsAppWebhookIngestionService service = new WhatsAppWebhookIngestionService(
                objectMapper, integrationService, eventRepository);
        String payload = """
                {"entry":[{"changes":[{"value":{
                  "metadata":{"phone_number_id":"123456789"},
                  "messages":[{"id":"wamid.abc","from":"919876543210","type":"text","text":{"body":"Hi"}}]
                }}]}]}
                """;

        int accepted = service.accept(payload.getBytes(StandardCharsets.UTF_8));

        assertThat(accepted).isEqualTo(1);
        verify(eventRepository).insert(
                eq(27), eq("123456789"), eq("wamid.abc"), eq("MESSAGE"),
                argThat(value -> value.contains("\"body\":\"Hi\"")));
    }

    @Test
    void ignoresEventsForUnconfiguredBusinessNumbers() {
        when(integrationService.findEnabledByPhoneNumberId("987654321")).thenReturn(Optional.empty());
        WhatsAppWebhookIngestionService service = new WhatsAppWebhookIngestionService(
                objectMapper, integrationService, eventRepository);
        String payload = """
                {"entry":[{"changes":[{"value":{
                  "metadata":{"phone_number_id":"987654321"},
                  "messages":[{"id":"wamid.unknown","from":"919876543210","type":"text","text":{"body":"Hi"}}]
                }}]}]}
                """;

        int accepted = service.accept(payload.getBytes(StandardCharsets.UTF_8));

        assertThat(accepted).isZero();
        verifyNoInteractions(eventRepository);
    }

    @Test
    void rejectsMalformedWebhookJson() {
        WhatsAppWebhookIngestionService service = new WhatsAppWebhookIngestionService(
                objectMapper, integrationService, eventRepository);

        assertThatThrownBy(() -> service.accept("{".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(eventRepository);
    }
}
