package org.ib.ibbackend.dto;

import java.math.BigDecimal;

public class InventoryUploadRow {
    private String product;
    private String sku;
    private Integer qty;
    private Integer minStock;
    private BigDecimal price;
    private String category = "General";

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }

    public Integer getMinStock() {
        return minStock;
    }

    public void setMinStock(Integer minStock) {
        this.minStock = minStock;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCategory() {
        return category == null || category.isBlank() ? "General" : category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}
