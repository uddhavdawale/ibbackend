
package org.ib.ibbackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "invoices", uniqueConstraints = {
    @UniqueConstraint(name = "uk_invoices_tenant_invoice_no", columnNames = {"tenant_id", "invoice_no"})
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Invoice {
    public enum InvoiceStatus {
        PENDING, PAID, CANCELLED
    }

    public enum PaymentStatus {
        PENDING, PARTIAL, PAID, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_no", nullable = false)
    private String invoiceNo;

    private String customerName;
    private String customerPhone;
    private LocalDate date;
    private String paymentMethod;

    private BigDecimal subtotal;
    private BigDecimal gstAmount;
    private BigDecimal grandTotal;

    @Column(nullable = false)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JsonManagedReference
    private List<InvoiceItem> items;
    @Enumerated(EnumType.STRING)

    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.PENDING;

    @PrePersist
    @PreUpdate
    private void normalizePaymentDefaults() {
        if (this.paidAmount == null) {
            this.paidAmount = BigDecimal.ZERO;
        }
        if (this.paymentStatus == null) {
            this.paymentStatus = PaymentStatus.PENDING;
        }
        if (this.status == null) {
            this.status = InvoiceStatus.PENDING;
        }
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @Transient
    private Long userId;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    // Constructors
    public Invoice() {}

    public Invoice(String invoiceNo, String customerName, String customerPhone,
                   LocalDate date, String paymentMethod, BigDecimal subtotal,
                   BigDecimal gstAmount, BigDecimal grandTotal, List<InvoiceItem> items) {
        this.invoiceNo = invoiceNo;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.date = date;
        this.paymentMethod = paymentMethod;
        this.subtotal = subtotal;
        this.gstAmount = gstAmount;
        this.grandTotal = grandTotal;
        this.items = items;
    }

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getInvoiceNo() { return invoiceNo; }
    public void setInvoiceNo(String invoiceNo) { this.invoiceNo = invoiceNo; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getGstAmount() { return gstAmount; }
    public void setGstAmount(BigDecimal gstAmount) { this.gstAmount = gstAmount; }

    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }

    public BigDecimal getPaidAmount() { return paidAmount == null ? BigDecimal.ZERO : paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount == null ? BigDecimal.ZERO : paidAmount; }

    public PaymentStatus getPaymentStatus() {
        if (status == InvoiceStatus.CANCELLED) {
            return PaymentStatus.CANCELLED;
        }

        BigDecimal total = grandTotal == null ? BigDecimal.ZERO : grandTotal;
        BigDecimal paid = getPaidAmount();

        if (paid.compareTo(total) >= 0 && total.compareTo(BigDecimal.ZERO) > 0) {
            return PaymentStatus.PAID;
        }
        if (paid.compareTo(BigDecimal.ZERO) > 0 && paid.compareTo(total) < 0) {
            return PaymentStatus.PARTIAL;
        }
        return PaymentStatus.PENDING;
    }

    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus == null ? PaymentStatus.PENDING : paymentStatus; }

    public BigDecimal getOutstandingAmount() {
        BigDecimal total = grandTotal == null ? BigDecimal.ZERO : grandTotal;
        return total.subtract(getPaidAmount()).max(BigDecimal.ZERO);
    }

    public BigDecimal getTotalAmount() {
        return grandTotal == null ? BigDecimal.ZERO : grandTotal;
    }

    public Integer getTenantId() {
        return tenant != null ? tenant.getId() : null;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }

    public List<InvoiceItem> getItems() { return items; }
    public void setItems(List<InvoiceItem> items) { this.items = items; }

    public InvoiceStatus getStatus() { return status == null ? InvoiceStatus.PENDING : status; }
    public void setStatus(InvoiceStatus status) { this.status = status == null ? InvoiceStatus.PENDING : status; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public static class ItemStub {
        private Long productId;
        private String name;
        private Integer qty;
        private BigDecimal price;
        private Integer gstRate;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public void setProductId(int productId) { this.productId = (long) productId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public Integer getQty() { return qty; }
        public void setQty(Integer qty) { this.qty = qty; }

        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }

        public Integer getGstRate() { return gstRate; }
        public void setGstRate(Integer gstRate) { this.gstRate = gstRate; }

        public InvoiceItem toInvoiceItem() {
            InvoiceItem item = new InvoiceItem();
            item.setProductId(productId);
            item.setName(name);
            item.setQty(qty);
            item.setPrice(price);
            item.setGstRate(gstRate);
            return item;
        }
    }

}