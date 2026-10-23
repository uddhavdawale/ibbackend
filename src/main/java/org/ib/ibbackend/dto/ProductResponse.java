package org.ib.ibbackend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductResponse(
        Long id,
        String name,
        String sku,
        Integer quantity,
        Integer minStock,
        BigDecimal price,
        String category,
        LocalDateTime createdAt,
        Integer tenantId
) {
    public ProductResponse(Long id, String name, String sku, BigDecimal price, Integer tenantId) {
        this(id, name, sku, 0, 0, price, "General", LocalDateTime.now(), tenantId);
    }
}