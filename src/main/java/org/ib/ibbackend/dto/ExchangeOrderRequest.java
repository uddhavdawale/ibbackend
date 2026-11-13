package org.ib.ibbackend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ExchangeOrderRequest {

    private Long invoiceId;
    private String reason;
    private BigDecimal newInvoiceAmount = BigDecimal.ZERO;
    @JsonAlias({"exchangeItems", "exchange_items", "items"})
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

    public BigDecimal getNewInvoiceAmount() {
        return newInvoiceAmount == null ? BigDecimal.ZERO : newInvoiceAmount;
    }

    public void setNewInvoiceAmount(BigDecimal newInvoiceAmount) {
        this.newInvoiceAmount = newInvoiceAmount;
    }

    public List<Item> getItems() {
        return items == null ? new ArrayList<>() : items;
    }

    public void setItems(List<Item> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }

    public static class Item {
        @JsonAlias({"product_id", "id", "productId"})
        private Long productId;
        @JsonAlias({"qty", "count", "returnQty"})
        private Integer quantity;
        @JsonAlias({"product_name", "name"})
        private String productName;
        @JsonAlias({"price", "amount"})
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
