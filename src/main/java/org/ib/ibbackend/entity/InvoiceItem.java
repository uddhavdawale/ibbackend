package org.ib.ibbackend.entity;


import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "invoice_items")
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ✅ CRITICAL: Add ManyToOne reference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")  // Maps to invoice_id column
    @JsonBackReference
    private Invoice invoice;

    private Long productId;

    @Column(nullable = false)
    private String name;

    private String sku;

    @Column(name = "hsn_code")
    private String hsn;

    @Column(name = "gst_rate")
    private Integer gstRate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price=BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer qty=1;

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
        BigDecimal safePrice = this.price == null ? BigDecimal.ZERO : this.price;
        int safeQty = this.qty == null ? 0 : this.qty;
        int safeGstRate = this.gstRate == null ? 0 : this.gstRate;

        this.itemSubtotal = safePrice.multiply(BigDecimal.valueOf(safeQty));
        this.itemGst = this.itemSubtotal.multiply(BigDecimal.valueOf(safeGstRate))
                .divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
        this.itemTotal = this.itemSubtotal.add(this.itemGst);
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }


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
    @JsonSetter(nulls = Nulls.AS_EMPTY)  // Jackson: null → empty string → parse to 0
    public void setPrice(BigDecimal price) {
        this.price = price;
        calculateTotals();  // Recalculate on price change
    }

    public Integer getQty() { return qty; }
    public void setQty(Integer qty) {
        this.qty = qty;
        calculateTotals();  // Recalculate on qty change
    }
    public Invoice getInvoice() { return invoice; }
    public void setInvoice(Invoice invoice) { this.invoice = invoice; }

    public BigDecimal getItemSubtotal() { return itemSubtotal; }
    public void setItemSubtotal(BigDecimal itemSubtotal) { this.itemSubtotal = itemSubtotal; }

    public BigDecimal getItemGst() { return itemGst; }
    public void setItemGst(BigDecimal itemGst) { this.itemGst = itemGst; }

    public BigDecimal getItemTotal() { return itemTotal; }
    public void setItemTotal(BigDecimal itemTotal) { this.itemTotal = itemTotal; }
}