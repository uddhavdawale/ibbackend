package org.ib.ibbackend.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductResponseTest {

    @Test
    void shouldAllowNullTenantId() {
        ProductResponse response = new ProductResponse(1L, "Keyboard", "KB-001", BigDecimal.valueOf(1999), null);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Keyboard");
        assertThat(response.tenantId()).isNull();
    }
}
