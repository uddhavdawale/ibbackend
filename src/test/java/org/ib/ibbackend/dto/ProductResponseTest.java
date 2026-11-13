package org.ib.ibbackend.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ib.ibbackend.entity.Product;
import org.ib.ibbackend.entity.Tenant;
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

    @Test
    void shouldSerializeTenantProxyWithoutHibernateInternals() throws Exception {
        Product product = new Product();
        product.setTenant(new TenantProxy());

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(product);

        assertThat(json).contains("\"tenant\"").doesNotContain("hibernateLazyInitializer", "handler");
    }

    private static class TenantProxy extends Tenant {
        public Object getHibernateLazyInitializer() {
            return new Object();
        }

        public Object getHandler() {
            return new Object();
        }
    }
}
