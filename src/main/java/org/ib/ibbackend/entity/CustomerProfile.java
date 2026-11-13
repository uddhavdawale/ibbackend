package org.ib.ibbackend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_profiles")
public class CustomerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "shop_name")
    private String shopName;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "gst_number")
    private String gstNumber;

    @Column(name = "phone")
    private String phone;

    @Column(name = "email")
    private String email;

    @Column(name = "due_balance", precision = 12, scale = 2)
    private BigDecimal dueBalance = BigDecimal.ZERO;

    @Column(name = "balance_due", precision = 12, scale = 2)
    private BigDecimal balanceDue = BigDecimal.ZERO;

    @Column(name = "outstanding_balance", precision = 12, scale = 2)
    private BigDecimal outstandingBalance = BigDecimal.ZERO;

    @Column(name = "pending_amount", precision = 12, scale = 2)
    private BigDecimal pendingAmount = BigDecimal.ZERO;

    @Column(name = "pending_balance", precision = 12, scale = 2)
    private BigDecimal pendingBalance = BigDecimal.ZERO;

    @Column(name = "amount_due", precision = 12, scale = 2)
    private BigDecimal amountDue = BigDecimal.ZERO;

    @Column(name = "total_due", precision = 12, scale = 2)
    private BigDecimal totalDue = BigDecimal.ZERO;

    @Column(name = "balance", precision = 12, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "receivable", precision = 12, scale = 2)
    private BigDecimal receivable = BigDecimal.ZERO;

    @Column(name = "outstanding", precision = 12, scale = 2)
    private BigDecimal outstanding = BigDecimal.ZERO;

    @Column(name = "previous_order_count")
    private Integer previousOrderCount = 0;

    @Column(name = "last_order_id")
    private Long lastOrderId;

    @Column(name = "last_order_no")
    private String lastOrderNo;

    @Column(name = "last_invoice_date")
    private LocalDate lastInvoiceDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getShopName() {
        return shopName;
    }

    public void setShopName(String shopName) {
        this.shopName = shopName;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getGstNumber() {
        return gstNumber;
    }

    public void setGstNumber(String gstNumber) {
        this.gstNumber = gstNumber;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public BigDecimal getDueBalance() {
        return dueBalance == null ? BigDecimal.ZERO : dueBalance;
    }

    public void setDueBalance(BigDecimal dueBalance) {
        this.dueBalance = dueBalance == null ? BigDecimal.ZERO : dueBalance;
    }

    public BigDecimal getBalanceDue() {
        return balanceDue == null ? BigDecimal.ZERO : balanceDue;
    }

    public void setBalanceDue(BigDecimal balanceDue) {
        this.balanceDue = balanceDue == null ? BigDecimal.ZERO : balanceDue;
    }

    public BigDecimal getOutstandingBalance() {
        return outstandingBalance == null ? BigDecimal.ZERO : outstandingBalance;
    }

    public void setOutstandingBalance(BigDecimal outstandingBalance) {
        this.outstandingBalance = outstandingBalance == null ? BigDecimal.ZERO : outstandingBalance;
    }

    public BigDecimal getPendingAmount() {
        return pendingAmount == null ? BigDecimal.ZERO : pendingAmount;
    }

    public void setPendingAmount(BigDecimal pendingAmount) {
        this.pendingAmount = pendingAmount == null ? BigDecimal.ZERO : pendingAmount;
    }

    public BigDecimal getPendingBalance() {
        return pendingBalance == null ? BigDecimal.ZERO : pendingBalance;
    }

    public void setPendingBalance(BigDecimal pendingBalance) {
        this.pendingBalance = pendingBalance == null ? BigDecimal.ZERO : pendingBalance;
    }

    public BigDecimal getAmountDue() {
        return amountDue == null ? BigDecimal.ZERO : amountDue;
    }

    public void setAmountDue(BigDecimal amountDue) {
        this.amountDue = amountDue == null ? BigDecimal.ZERO : amountDue;
    }

    public BigDecimal getTotalDue() {
        return totalDue == null ? BigDecimal.ZERO : totalDue;
    }

    public void setTotalDue(BigDecimal totalDue) {
        this.totalDue = totalDue == null ? BigDecimal.ZERO : totalDue;
    }

    public BigDecimal getBalance() {
        return balance == null ? BigDecimal.ZERO : balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance == null ? BigDecimal.ZERO : balance;
    }

    public BigDecimal getReceivable() {
        return receivable == null ? BigDecimal.ZERO : receivable;
    }

    public void setReceivable(BigDecimal receivable) {
        this.receivable = receivable == null ? BigDecimal.ZERO : receivable;
    }

    public BigDecimal getOutstanding() {
        return outstanding == null ? BigDecimal.ZERO : outstanding;
    }

    public void setOutstanding(BigDecimal outstanding) {
        this.outstanding = outstanding == null ? BigDecimal.ZERO : outstanding;
    }

    public void applyOutstandingAmount(BigDecimal outstandingAmount) {
        BigDecimal normalized = outstandingAmount == null ? BigDecimal.ZERO : outstandingAmount.max(BigDecimal.ZERO);
        setDueBalance(normalized);
        setBalanceDue(normalized);
        setOutstandingBalance(normalized);
        setPendingAmount(normalized);
        setPendingBalance(normalized);
        setAmountDue(normalized);
        setTotalDue(normalized);
        setBalance(normalized);
        setReceivable(normalized);
        setOutstanding(normalized);
    }

    public Integer getPreviousOrderCount() {
        return previousOrderCount == null ? 0 : previousOrderCount;
    }

    public void setPreviousOrderCount(Integer previousOrderCount) {
        this.previousOrderCount = previousOrderCount == null ? 0 : previousOrderCount;
    }

    public Long getLastOrderId() {
        return lastOrderId;
    }

    public void setLastOrderId(Long lastOrderId) {
        this.lastOrderId = lastOrderId;
    }

    public String getLastOrderNo() {
        return lastOrderNo;
    }

    public void setLastOrderNo(String lastOrderNo) {
        this.lastOrderNo = lastOrderNo;
    }

    public LocalDate getLastInvoiceDate() {
        return lastInvoiceDate;
    }

    public void setLastInvoiceDate(LocalDate lastInvoiceDate) {
        this.lastInvoiceDate = lastInvoiceDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
