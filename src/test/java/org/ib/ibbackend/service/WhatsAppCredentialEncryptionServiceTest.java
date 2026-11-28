package org.ib.ibbackend.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhatsAppCredentialEncryptionServiceTest {
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsAndDecryptsCredentialsForTheirTenant() {
        WhatsAppCredentialEncryptionService service = new WhatsAppCredentialEncryptionService(key);

        String encrypted = service.encrypt(42, "shop-access-token");

        assertThat(encrypted).isNotEqualTo("shop-access-token");
        assertThat(service.decrypt(42, encrypted)).isEqualTo("shop-access-token");
    }

    @Test
    void preventsDecryptingCredentialsUnderAnotherTenant() {
        WhatsAppCredentialEncryptionService service = new WhatsAppCredentialEncryptionService(key);
        String encrypted = service.encrypt(42, "shop-access-token");

        assertThatThrownBy(() -> service.decrypt(43, encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportsMissingEncryptionConfiguration() {
        WhatsAppCredentialEncryptionService service = new WhatsAppCredentialEncryptionService("");

        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(() -> service.encrypt(42, "shop-access-token"))
                .isInstanceOf(IllegalStateException.class);
    }
}
