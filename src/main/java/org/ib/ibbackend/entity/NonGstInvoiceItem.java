package org.ib.ibbackend.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "non_gst_invoice_items")
public class NonGstInvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    @JsonBackReference
    private NonGstInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "sku")
    private String sku;

    @Column(name = "hsn_code")
    private String hsn;

    @Column(name = "category")
    private String category;

    @Column(name = "gst_rate", nullable = false)
    private Integer gstRate = 0;

    @Column(name = "cgst", nullable = false, precision = 12, scale = 2)
    private BigDecimal cgst = BigDecimal.ZERO;

    @Column(name = "sgst", nullable = false, precision = 12, scale = 2)
    private BigDecimal sgst = BigDecimal.ZERO;

    @Column(name = "igst", nullable = false, precision = 12, scale = 2)
    private BigDecimal igst = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(name = "qty", nullable = false)
    private Integer qty = 1;

    @Column(name = "item_subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal itemSubtotal = BigDecimal.ZERO;

    @Column(name = "item_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal itemTotal = BigDecimal.ZERO;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public NonGstInvoice getInvoice() {
        return invoice;
    }

    public void setInvoice(NonGstInvoice invoice) {
        this.invoice = invoice;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getHsn() {
        return hsn;
    }

    public void setHsn(String hsn) {
        this.hsn = hsn;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getGstRate() {
        return gstRate;
    }

    public void setGstRate(Integer gstRate) {
        this.gstRate = gstRate;
    }

    public BigDecimal getCgst() {
        return cgst;
    }

    public void setCgst(BigDecimal cgst) {
        this.cgst = cgst;
    }

    public BigDecimal getSgst() {
        return sgst;
    }

    public void setSgst(BigDecimal sgst) {
        this.sgst = sgst;
    }

    public BigDecimal getIgst() {
        return igst;
    }

    public void setIgst(BigDecimal igst) {
        this.igst = igst;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }

    public BigDecimal getItemSubtotal() {
        return itemSubtotal;
    }

    public void setItemSubtotal(BigDecimal itemSubtotal) {
        this.itemSubtotal = itemSubtotal;
    }

    public BigDecimal getItemTotal() {
        return itemTotal;
    }

    public void setItemTotal(BigDecimal itemTotal) {
        this.itemTotal = itemTotal;
    }
}
