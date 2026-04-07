package org.ib.ibbackend.entity;


import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "invoice_items")
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 👈 Foreign key to Invoice
    @Column(name = "invoice_id", insertable = false, updatable = false)
    private Long invoiceId;

    private Long productId;

    @Column(nullable = false)
    private String name;

    private String sku;

    @Column(name = "hsn_code")
    private String hsn;

    @Column(name = "gst_rate")
    private Integer gstRate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer qty;

    // 👈 Calculated fields (stored for performance)
    @Column(name = "item_subtotal", precision = 10, scale = 2)
    private BigDecimal itemSubtotal;

    @Column(name = "item_gst", precision = 10, scale = 2)
    private BigDecimal itemGst;

    @Column(name = "item_total", precision = 10, scale = 2)
    private BigDecimal itemTotal;

    // Default constructor (JPA required)
    public InvoiceItem() {}

    // Constructor for creating from Product + Cart data
    public InvoiceItem(Long productId, String name, String sku, String hsn,
                       Integer gstRate, BigDecimal price, Integer qty) {
        this.productId = productId;
        this.name = name;
        this.sku = sku;
        this.hsn = hsn;
        this.gstRate = gstRate;
        this.price = price;
        this.qty = qty;

        // Auto-calculate totals
        calculateTotals();
    }

    // 👈 Auto-calculate item totals
    public void calculateTotals() {
        this.itemSubtotal = this.price.multiply(BigDecimal.valueOf(this.qty));
        this.itemGst = this.itemSubtotal.multiply(BigDecimal.valueOf(this.gstRate))
                .divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
        this.itemTotal = this.itemSubtotal.add(this.itemGst);
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getInvoiceId() { return invoiceId; }
    public void setInvoiceId(Long invoiceId) { this.invoiceId = invoiceId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public String getHsn() { return hsn; }
    public void setHsn(String hsn) { this.hsn = hsn; }

    public Integer getGstRate() { return gstRate; }
    public void setGstRate(Integer gstRate) {
        this.gstRate = gstRate;
        calculateTotals();  // Recalculate on GST change
    }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) {
        this.price = price;
        calculateTotals();  // Recalculate on price change
    }

    public Integer getQty() { return qty; }
    public void setQty(Integer qty) {
        this.qty = qty;
        calculateTotals();  // Recalculate on qty change
    }

    public BigDecimal getItemSubtotal() { return itemSubtotal; }
    public void setItemSubtotal(BigDecimal itemSubtotal) { this.itemSubtotal = itemSubtotal; }

    public BigDecimal getItemGst() { return itemGst; }
    public void setItemGst(BigDecimal itemGst) { this.itemGst = itemGst; }

    public BigDecimal getItemTotal() { return itemTotal; }
    public void setItemTotal(BigDecimal itemTotal) { this.itemTotal = itemTotal; }
}