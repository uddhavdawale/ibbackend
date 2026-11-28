package org.ib.ibbackend.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.filter.TenantContext;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.repository.WhatsAppIntegrationRepository.IntegrationSettings;
import org.ib.ibbackend.security.UserRolePolicy;
import org.ib.ibbackend.service.AuditLogService;
import org.ib.ibbackend.service.WhatsAppIntegrationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/integrations/whatsapp")
public class WhatsAppIntegrationController {
    private final UserRepository userRepository;
    private final WhatsAppIntegrationService integrationService;
    private final AuditLogService auditLogService;
    private final String webhookUrl;

    public WhatsAppIntegrationController(
            UserRepository userRepository,
            WhatsAppIntegrationService integrationService,
            AuditLogService auditLogService,
            @Value("${app.whatsapp.webhook-url:}") String webhookUrl) {
        this.userRepository = userRepository;
        this.integrationService = integrationService;
        this.auditLogService = auditLogService;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    }

    @GetMapping
    public Map<String, Object> getIntegration() {
        User user = requireStoreAdmin();
        int tenantId = requireTenantId(user);
        return response(integrationService.findForTenant(tenantId));
    }

    @PutMapping
    public Map<String, Object> saveIntegration(@Valid @RequestBody IntegrationRequest request) {
        User user = requireStoreAdmin();
        int tenantId = requireTenantId(user);
        IntegrationSettings settings = integrationService.save(
                tenantId,
                request.phoneNumberId().trim(),
                request.wabaId().trim(),
                request.displayPhoneNumber().trim(),
                request.accessToken());
        auditLogService.write(
                "WHATSAPP_INTEGRATION_UPDATED",
                String.valueOf(tenantId),
                "SUCCESS",
                "Retailer WhatsApp integration updated",
                user.getId(),
                (long) tenantId);
        return response(Optional.of(settings));
    }

    @DeleteMapping
    public Map<String, Object> disconnectIntegration() {
        User user = requireStoreAdmin();
        int tenantId = requireTenantId(user);
        boolean removed = integrationService.disconnect(tenantId);
        if (removed) {
            auditLogService.write(
                    "WHATSAPP_INTEGRATION_DISCONNECTED",
                    String.valueOf(tenantId),
                    "SUCCESS",
                    "Retailer WhatsApp integration disconnected",
                    user.getId(),
                    (long) tenantId);
        }
        Map<String, Object> integration = new LinkedHashMap<>();
        integration.put("connected", false);
        integration.put("tokenConfigured", false);
        integration.put("webhookUrl", nonBlankOrNull(webhookUrl));
        return Map.of("success", true, "integration", integration);
    }

    private Map<String, Object> response(Optional<IntegrationSettings> settings) {
        Map<String, Object> integration = new LinkedHashMap<>();
        IntegrationSettings configured = settings.filter(IntegrationSettings::enabled).orElse(null);
        integration.put("connected", configured != null);
        integration.put("tokenConfigured", configured != null);
        integration.put("phoneNumberId", configured == null ? null : configured.phoneNumberId());
        integration.put("wabaId", configured == null ? null : configured.wabaId());
        integration.put("displayPhoneNumber", configured == null ? null : configured.displayPhoneNumber());
        integration.put("webhookUrl", nonBlankOrNull(webhookUrl));
        return Map.of("success", true, "integration", integration);
    }

    private String nonBlankOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private User requireStoreAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new AccessDeniedException("Access denied");
        }
        User user = userRepository.findByEmail(authentication.getName())
                .or(() -> userRepository.findByUsername(authentication.getName()))
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
        if (user.isDisabled() || !UserRolePolicy.isStoreAdmin(user.getRole())) {
            throw new AccessDeniedException("Only a shop owner can manage WhatsApp integration");
        }
        return user;
    }

    private int requireTenantId(User user) {
        Tenant tenant = user.getTenant();
        if (tenant == null) {
            throw new AccessDeniedException("This account is not associated with a shop");
        }
        Long contextTenantId = TenantContext.getCurrentTenant();
        if (contextTenantId != null && contextTenantId != tenant.getId()) {
            throw new AccessDeniedException("Shop access denied");
        }
        return tenant.getId();
    }

    public record IntegrationRequest(
            @NotBlank
            @Pattern(regexp = "^[0-9]{1,64}$")
            String phoneNumberId,
            @NotBlank
            @Pattern(regexp = "^[0-9]{1,64}$")
            String wabaId,
            @NotBlank
            @Size(max = 32)
            @Pattern(regexp = "^(?=.*[0-9])[+()0-9 .-]{7,32}$")
            String displayPhoneNumber,
            @Size(max = 4096)
            String accessToken) {
    }
}
