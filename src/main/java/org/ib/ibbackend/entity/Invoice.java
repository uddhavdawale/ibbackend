
package org.ib.ibbackend.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "invoices")
public class Invoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String invoiceNo;

    private String customerName;
    private String customerPhone;
    private LocalDate date;
    private String paymentMethod;

    private BigDecimal subtotal;
    private BigDecimal gstAmount;
    private BigDecimal grandTotal;

    @OneToMany(mappedBy = "invoiceId", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    private List<InvoiceItem> items;

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

    public List<InvoiceItem> getItems() { return items; }
    public void setItems(List<InvoiceItem> items) { this.items = items; }
}