package org.ib.ibbackend.service;

import org.ib.ibbackend.repository.WhatsAppIntegrationRepository;
import org.ib.ibbackend.repository.WhatsAppIntegrationRepository.IntegrationSettings;
import org.ib.ibbackend.repository.WhatsAppWebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppIntegrationServiceTest {
    @Mock
    private WhatsAppIntegrationRepository integrationRepository;

    @Mock
    private WhatsAppCloudApiClient cloudApiClient;

    @Mock
    private WhatsAppWebhookEventRepository eventRepository;

    @Test
    void encryptsAndValidatesTokenBeforePersisting() {
        int tenantId = 12;
        WhatsAppCredentialEncryptionService encryptionService = encryptionService();
        IntegrationSettings saved = new IntegrationSettings(
                tenantId, "123456789", "987654321", "+919876543210",
                encryptionService.encrypt(tenantId, "meta-secret"), true);
        when(integrationRepository.findByTenantId(tenantId))
                .thenReturn(Optional.empty(), Optional.of(saved));
        when(integrationRepository.findEnabledByPhoneNumberId("123456789")).thenReturn(Optional.empty());
        WhatsAppIntegrationService service = new WhatsAppIntegrationService(
                integrationRepository, encryptionService, cloudApiClient, eventRepository);

        IntegrationSettings result = service.save(
                tenantId, "123456789", "987654321", "+919876543210", "meta-secret");

        verify(cloudApiClient).validateCredentials("123456789", "meta-secret");
        verify(integrationRepository).save(
                eq(tenantId), eq("123456789"), eq("987654321"), eq("+919876543210"),
                argThat(value -> !value.equals("meta-secret")
                        && encryptionService.decrypt(tenantId, value).equals("meta-secret")));
        assertThat(result.encryptedAccessToken()).isNotEqualTo("meta-secret");
    }

    @Test
    void requiresANewTokenWhenChangingTheTenantPhoneNumber() {
        int tenantId = 12;
        IntegrationSettings current = new IntegrationSettings(
                tenantId, "123456789", "987654321", "+919876543210", "encrypted", true);
        when(integrationRepository.findByTenantId(tenantId)).thenReturn(Optional.of(current));
        WhatsAppIntegrationService service = new WhatsAppIntegrationService(
                integrationRepository, encryptionService(), cloudApiClient, eventRepository);

        assertThatThrownBy(() -> service.save(
                tenantId, "123456788", "987654321", "+919876543210", ""))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(cloudApiClient);
        verify(integrationRepository, never()).save(anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    private WhatsAppCredentialEncryptionService encryptionService() {
        return new WhatsAppCredentialEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
    }
}
