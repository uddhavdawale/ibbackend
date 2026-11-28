package org.ib.ibbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.repository.WhatsAppIntegrationRepository.IntegrationSettings;
import org.ib.ibbackend.repository.WhatsAppWebhookEventRepository;
import org.ib.ibbackend.repository.WhatsAppWebhookEventRepository.QueuedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class WhatsAppWebhookEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookEventProcessor.class);
    private static final String GREETING =
            "Hi! I can help you create an invoice or check pending dues.\n"
                    + "Try: \"Bill Sharma ji 10 kg rice, 2 oil, UPI\"";

    private final WhatsAppWebhookEventRepository eventRepository;
    private final WhatsAppIntegrationService integrationService;
    private final WhatsAppCredentialEncryptionService encryptionService;
    private final WhatsAppCloudApiClient cloudApiClient;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final int batchSize;

    public WhatsAppWebhookEventProcessor(
            WhatsAppWebhookEventRepository eventRepository,
            WhatsAppIntegrationService integrationService,
            WhatsAppCredentialEncryptionService encryptionService,
            WhatsAppCloudApiClient cloudApiClient,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            @Value("${app.whatsapp.worker-batch-size:10}") int batchSize) {
        this.eventRepository = eventRepository;
        this.integrationService = integrationService;
        this.encryptionService = encryptionService;
        this.cloudApiClient = cloudApiClient;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.batchSize = Math.max(1, Math.min(batchSize, 50));
    }

    @Scheduled(fixedDelayString = "${app.whatsapp.worker-delay-ms:2000}")
    public void processPendingEvents() {
        List<QueuedEvent> events = eventRepository.claimBatch(batchSize);
        for (QueuedEvent event : events) {
            try {
                process(event);
                eventRepository.markProcessed(event.id());
            } catch (Exception ex) {
                eventRepository.markFailed(event.id(), "PROCESSING_FAILED");
                log.warn("WhatsApp webhook event processing failed; eventId={}", event.id());
            }
        }
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void deleteExpiredEvents() {
        eventRepository.deleteExpiredEvents();
    }

    private void process(QueuedEvent event) throws Exception {
        if ("DELIVERY_STATUS".equals(event.eventType())) {
            return;
        }

        Optional<IntegrationSettings> integrationResult = integrationService
                .findEnabledByPhoneNumberId(event.phoneNumberId());
        if (integrationResult.isEmpty() || integrationResult.get().tenantId() != event.tenantId()) {
            auditLogService.write(
                    "WHATSAPP_EVENT_IGNORED",
                    event.providerEventId(),
                    "DENIED",
                    "Shop integration is no longer active",
                    null,
                    (long) event.tenantId());
            return;
        }
        IntegrationSettings integration = integrationResult.get();

        JsonNode message = objectMapper.readTree(event.payload());
        String sender = message.path("from").asText("");
        User owner = findAuthorizedOwner(event.tenantId(), sender);
        if (owner == null) {
            auditLogService.write(
                    "WHATSAPP_MESSAGE_IGNORED",
                    event.providerEventId(),
                    "DENIED",
                    "Sender is not a registered active shop owner",
                    null,
                    (long) event.tenantId());
            return;
        }

        if (!"text".equalsIgnoreCase(message.path("type").asText(""))) {
            return;
        }

        String text = message.path("text").path("body").asText("");
        String accessToken = encryptionService.decrypt(
                event.tenantId(), integration.encryptedAccessToken());
        cloudApiClient.sendText(
                integration.phoneNumberId(),
                accessToken,
                sender,
                responseFor(text));
        auditLogService.write(
                "WHATSAPP_MESSAGE_PROCESSED",
                event.providerEventId(),
                "SUCCESS",
                "Processed owner WhatsApp message",
                owner.getId(),
                (long) event.tenantId());
    }

    private User findAuthorizedOwner(int tenantId, String sender) {
        String normalizedSender = normalizeIndianPhone(sender);
        if (!StringUtils.hasText(normalizedSender)) {
            return null;
        }
        return userRepository.findTenantStoreAdmins(tenantId).stream()
                .filter(user -> !user.isDisabled())
                .filter(user -> normalizeIndianPhone(user.getPhone()).equals(normalizedSender))
                .findFirst()
                .orElse(null);
    }

    private String responseFor(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.equals("hi") || normalized.equals("hello")
                || normalized.equals("hey") || normalized.equals("help")) {
            return GREETING;
        }
        return GREETING;
    }

    private String normalizeIndianPhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() == 10) {
            return "91" + digits;
        }
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits;
        }
        return digits;
    }
}
