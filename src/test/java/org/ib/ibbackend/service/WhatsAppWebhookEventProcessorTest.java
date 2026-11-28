package org.ib.ibbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.repository.WhatsAppIntegrationRepository.IntegrationSettings;
import org.ib.ibbackend.repository.WhatsAppWebhookEventRepository;
import org.ib.ibbackend.repository.WhatsAppWebhookEventRepository.QueuedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Base64;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookEventProcessorTest {
    @Mock
    private WhatsAppWebhookEventRepository eventRepository;

    @Mock
    private WhatsAppIntegrationService integrationService;

    @Mock
    private WhatsAppCloudApiClient cloudApiClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogService auditLogService;

    @Test
    void repliesOnlyToTheRegisteredOwnerForTheMappedTenant() {
        int tenantId = 27;
        String phoneNumberId = "123456789";
        String sender = "919876543210";
        String encryptedToken = new WhatsAppCredentialEncryptionService(
                Base64.getEncoder().encodeToString(new byte[32])).encrypt(tenantId, "shop-token");
        when(eventRepository.claimBatch(10)).thenReturn(List.of(new QueuedEvent(
                1L, tenantId, phoneNumberId, "wamid.owner", "MESSAGE",
                "{\"id\":\"wamid.owner\",\"from\":\"919876543210\",\"type\":\"text\",\"text\":{\"body\":\"Hi\"}}")));
        when(integrationService.findEnabledByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(
                new IntegrationSettings(tenantId, phoneNumberId, "987654321", "+919876543210", encryptedToken, true)));
        User owner = new User();
        owner.setId(7L);
        owner.setPhone("9876543210");
        when(userRepository.findTenantStoreAdmins(tenantId)).thenReturn(List.of(owner));

        processor().processPendingEvents();

        verify(cloudApiClient).sendText(eq(phoneNumberId), eq("shop-token"), eq(sender),
                contains("I can help you create an invoice"));
        verify(eventRepository).markProcessed(1L);
        verify(eventRepository, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void doesNotReplyToUnregisteredSenders() {
        int tenantId = 27;
        String phoneNumberId = "123456789";
        when(eventRepository.claimBatch(10)).thenReturn(List.of(new QueuedEvent(
                2L, tenantId, phoneNumberId, "wamid.unknown", "MESSAGE",
                "{\"id\":\"wamid.unknown\",\"from\":\"919900000000\",\"type\":\"text\",\"text\":{\"body\":\"Hi\"}}")));
        when(integrationService.findEnabledByPhoneNumberId(phoneNumberId)).thenReturn(Optional.of(
                new IntegrationSettings(tenantId, phoneNumberId, "987654321", "+919876543210", "unused", true)));
        when(userRepository.findTenantStoreAdmins(tenantId)).thenReturn(List.of());

        processor().processPendingEvents();

        verifyNoInteractions(cloudApiClient);
        verify(eventRepository).markProcessed(2L);
        verify(auditLogService).write(
                eq("WHATSAPP_MESSAGE_IGNORED"), eq("wamid.unknown"), eq("DENIED"),
                anyString(), isNull(), eq((long) tenantId));
    }

    private WhatsAppWebhookEventProcessor processor() {
        return new WhatsAppWebhookEventProcessor(
                eventRepository,
                integrationService,
                new WhatsAppCredentialEncryptionService(Base64.getEncoder().encodeToString(new byte[32])),
                cloudApiClient,
                userRepository,
                new ObjectMapper(),
                auditLogService,
                10);
    }
}
