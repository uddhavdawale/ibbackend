package org.ib.ibbackend.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ReturnOrderRequest {

    private Long invoiceId;
    private String reason;
    private List<Item> items = new ArrayList<>();

    public Long getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(Long invoiceId) {
        this.invoiceId = invoiceId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }

    public static class Item {
        private Long productId;
        private Integer quantity;
        private String productName;
        private BigDecimal unitPrice;

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public Integer getQuantity() {
            return quantity == null ? 0 : quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public BigDecimal getUnitPrice() {
            return unitPrice == null ? BigDecimal.ZERO : unitPrice;
        }

        public void setUnitPrice(BigDecimal unitPrice) {
            this.unitPrice = unitPrice;
        }
    }
}
