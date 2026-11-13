package org.ib.ibbackend.dto;

import java.math.BigDecimal;

public class InventoryUploadRow {
    private String product;
    private String sku;
    private String hsnSac;
    private Integer qty;
    private Integer minStock;
    private String set = "1";
    private BigDecimal price;
    private BigDecimal mrp;
    private BigDecimal pricePerQty;
    private String poNumber;
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

    public String getHsnSac() {
        return hsnSac;
    }

    public void setHsnSac(String hsnSac) {
        this.hsnSac = hsnSac;
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

    public String getSet() {
        return set;
    }

    public void setSet(String set) {
        this.set = set;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public void setMrp(BigDecimal mrp) {
        this.mrp = mrp;
    }

    public BigDecimal getPricePerQty() {
        return pricePerQty;
    }

    public void setPricePerQty(BigDecimal pricePerQty) {
        this.pricePerQty = pricePerQty;
    }

    public String getPoNumber() {
        return poNumber;
    }

    public void setPoNumber(String poNumber) {
        this.poNumber = poNumber;
    }

    public String getCategory() {
        return category == null || category.isBlank() ? "General" : category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}
