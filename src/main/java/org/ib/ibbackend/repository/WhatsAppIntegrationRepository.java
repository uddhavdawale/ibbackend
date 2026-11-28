package org.ib.ibbackend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class WhatsAppIntegrationRepository {
    private final JdbcTemplate jdbcTemplate;

    public WhatsAppIntegrationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<IntegrationSettings> findByTenantId(int tenantId) {
        return jdbcTemplate.query("""
                        SELECT tenant_id, phone_number_id, waba_id, display_phone_number,
                               access_token_ciphertext, enabled
                        FROM whatsapp_integrations
                        WHERE tenant_id = ?
                        """,
                (resultSet, rowNumber) -> new IntegrationSettings(
                        resultSet.getInt("tenant_id"),
                        resultSet.getString("phone_number_id"),
                        resultSet.getString("waba_id"),
                        resultSet.getString("display_phone_number"),
                        resultSet.getString("access_token_ciphertext"),
                        resultSet.getBoolean("enabled")),
                tenantId).stream().findFirst();
    }

    public Optional<IntegrationSettings> findEnabledByPhoneNumberId(String phoneNumberId) {
        return jdbcTemplate.query("""
                        SELECT tenant_id, phone_number_id, waba_id, display_phone_number,
                               access_token_ciphertext, enabled
                        FROM whatsapp_integrations
                        WHERE phone_number_id = ? AND enabled = TRUE
                        """,
                (resultSet, rowNumber) -> new IntegrationSettings(
                        resultSet.getInt("tenant_id"),
                        resultSet.getString("phone_number_id"),
                        resultSet.getString("waba_id"),
                        resultSet.getString("display_phone_number"),
                        resultSet.getString("access_token_ciphertext"),
                        resultSet.getBoolean("enabled")),
                phoneNumberId).stream().findFirst();
    }

    public void save(int tenantId,
                     String phoneNumberId,
                     String wabaId,
                     String displayPhoneNumber,
                     String encryptedAccessToken) {
        jdbcTemplate.update("""
                INSERT INTO whatsapp_integrations (
                    tenant_id, phone_number_id, waba_id, display_phone_number,
                    access_token_ciphertext, enabled, updated_at
                )
                VALUES (?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    phone_number_id = EXCLUDED.phone_number_id,
                    waba_id = EXCLUDED.waba_id,
                    display_phone_number = EXCLUDED.display_phone_number,
                    access_token_ciphertext = EXCLUDED.access_token_ciphertext,
                    enabled = TRUE,
                    updated_at = CURRENT_TIMESTAMP
                """,
                tenantId, phoneNumberId, wabaId, displayPhoneNumber, encryptedAccessToken);
    }

    public int deleteByTenantId(int tenantId) {
        return jdbcTemplate.update("DELETE FROM whatsapp_integrations WHERE tenant_id = ?", tenantId);
    }

    public record IntegrationSettings(
            int tenantId,
            String phoneNumberId,
            String wabaId,
            String displayPhoneNumber,
            String encryptedAccessToken,
            boolean enabled) {
    }
}
