package org.ib.ibbackend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductResponse(
        Long id,
        String name,
        String sku,
        String hsn,
        Integer quantity,
        Integer minStock,
        BigDecimal price,
        BigDecimal mrp,
        String set,
        String poNumber,
        Integer salesCount,
        String category,
        LocalDateTime createdAt,
        Integer tenantId,
        Long uploadedById
) {
    public ProductResponse(Long id, String name, String sku, BigDecimal price, Integer tenantId) {
        this(id, name, sku, null, 0, 0, price, price, "1", null, 0, "General", LocalDateTime.now(), tenantId, null);
    }
}