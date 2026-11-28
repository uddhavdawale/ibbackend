package org.ib.ibbackend.service;

import org.ib.ibbackend.repository.WhatsAppIntegrationRepository;
import org.ib.ibbackend.repository.WhatsAppIntegrationRepository.IntegrationSettings;
import org.ib.ibbackend.repository.WhatsAppWebhookEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

@Service
public class WhatsAppIntegrationService {
    private final WhatsAppIntegrationRepository integrationRepository;
    private final WhatsAppCredentialEncryptionService encryptionService;
    private final WhatsAppCloudApiClient cloudApiClient;
    private final WhatsAppWebhookEventRepository eventRepository;

    public WhatsAppIntegrationService(
            WhatsAppIntegrationRepository integrationRepository,
            WhatsAppCredentialEncryptionService encryptionService,
            WhatsAppCloudApiClient cloudApiClient,
            WhatsAppWebhookEventRepository eventRepository) {
        this.integrationRepository = integrationRepository;
        this.encryptionService = encryptionService;
        this.cloudApiClient = cloudApiClient;
        this.eventRepository = eventRepository;
    }

    @Transactional(readOnly = true)
    public Optional<IntegrationSettings> findForTenant(int tenantId) {
        return integrationRepository.findByTenantId(tenantId);
    }

    public IntegrationSettings save(int tenantId,
                                    String phoneNumberId,
                                    String wabaId,
                                    String displayPhoneNumber,
                                    String accessToken) {
        Optional<IntegrationSettings> current = integrationRepository.findByTenantId(tenantId);
        String encryptedToken;
        String plaintextToken;
        if (StringUtils.hasText(accessToken)) {
            if (!encryptionService.isConfigured()) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "WhatsApp credential encryption is not configured on the server");
            }
            plaintextToken = accessToken.trim();
            encryptedToken = encryptionService.encrypt(tenantId, plaintextToken);
        } else {
            IntegrationSettings existing = current
                    .filter(IntegrationSettings::enabled)
                    .filter(settings -> settings.phoneNumberId().equals(phoneNumberId))
                    .filter(settings -> settings.wabaId().equals(wabaId))
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Enter a new access token when changing the connected account"));
            if (!encryptionService.isConfigured()) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "WhatsApp credential encryption is not configured on the server");
            }
            encryptedToken = existing.encryptedAccessToken();
            plaintextToken = encryptionService.decrypt(tenantId, encryptedToken);
        }

        integrationRepository.findEnabledByPhoneNumberId(phoneNumberId)
                .filter(existing -> existing.tenantId() != tenantId)
                .ifPresent(existing -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "This WhatsApp phone number is already connected to another shop");
                });

        try {
            cloudApiClient.validateCredentials(phoneNumberId, plaintextToken);
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Meta rejected these WhatsApp credentials or permissions");
        } catch (ResourceAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Meta could not be reached to verify WhatsApp settings");
        }

        try {
            integrationRepository.save(
                    tenantId, phoneNumberId, wabaId, displayPhoneNumber, encryptedToken);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This WhatsApp phone number is already connected to another shop");
        }

        return integrationRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp integration was not saved"));
    }

    @Transactional(readOnly = true)
    public Optional<IntegrationSettings> findEnabledByPhoneNumberId(String phoneNumberId) {
        return integrationRepository.findEnabledByPhoneNumberId(phoneNumberId);
    }


    @Transactional
    public boolean disconnect(int tenantId) {
        boolean removed = integrationRepository.deleteByTenantId(tenantId) > 0;
        if (removed) {
            eventRepository.cancelPendingForTenant(tenantId);
        }
        return removed;
    }
}
