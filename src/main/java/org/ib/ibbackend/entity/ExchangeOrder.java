package org.ib.ibbackend.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "exchange_orders")
public class ExchangeOrder {

    public enum Status {
        CREATED,
        COMPLETED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "invoice_no", nullable = false)
    private String invoiceNo;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "exchange_date")
    private LocalDate exchangeDate = LocalDate.now();

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "credit_amount", precision = 12, scale = 2)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column(name = "new_invoice_amount", precision = 12, scale = 2)
    private BigDecimal newInvoiceAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.CREATED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @OneToMany(mappedBy = "exchangeOrder", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JsonManagedReference
    private List<ExchangeOrderItem> items = new ArrayList<>();

    public ExchangeOrder() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(Long invoiceId) {
        this.invoiceId = invoiceId;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public void setInvoiceNo(String invoiceNo) {
        this.invoiceNo = invoiceNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public LocalDate getExchangeDate() {
        return exchangeDate;
    }

    public void setExchangeDate(LocalDate exchangeDate) {
        this.exchangeDate = exchangeDate;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public BigDecimal getCreditAmount() {
        return creditAmount == null ? BigDecimal.ZERO : creditAmount;
    }

    public void setCreditAmount(BigDecimal creditAmount) {
        this.creditAmount = creditAmount == null ? BigDecimal.ZERO : creditAmount;
    }

    public BigDecimal getNewInvoiceAmount() {
        return newInvoiceAmount == null ? BigDecimal.ZERO : newInvoiceAmount;
    }

    public void setNewInvoiceAmount(BigDecimal newInvoiceAmount) {
        this.newInvoiceAmount = newInvoiceAmount == null ? BigDecimal.ZERO : newInvoiceAmount;
    }

    public Status getStatus() {
        return status == null ? Status.CREATED : status;
    }

    public void setStatus(Status status) {
        this.status = status == null ? Status.CREATED : status;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public List<ExchangeOrderItem> getItems() {
        return items;
    }

    public void setItems(List<ExchangeOrderItem> items) {
        this.items = items == null ? new ArrayList<>() : items;
        for (ExchangeOrderItem item : this.items) {
            item.setExchangeOrder(this);
        }
    }
}
