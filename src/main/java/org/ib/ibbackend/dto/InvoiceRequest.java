package org.ib.ibbackend.dto;

import org.ib.ibbackend.entity.InvoiceItem;
import java.math.BigDecimal;
import java.util.List;

public class InvoiceRequest {
    private String customerName;
    private String customerPhone;
    private String paymentMethod;
    private String status;
    private String paymentStatus;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    private List<InvoiceItem> items;

    // ✅ Jackson needs no-arg constructor
    public InvoiceRequest() {}

    // ✅ Convenience constructor
    public InvoiceRequest(String customerName, String customerPhone,
                          String paymentMethod, BigDecimal paidAmount,
                          String status, List<InvoiceItem> items) {
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.paymentMethod = paymentMethod;
        this.paidAmount = paidAmount == null ? BigDecimal.ZERO : paidAmount;
        this.status = status;
        this.items = items;
    }

    // ✅ Explicit getters/setters
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }

    public BigDecimal getPaidAmount() { return paidAmount == null ? BigDecimal.ZERO : paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount == null ? BigDecimal.ZERO : paidAmount; }

    public List<InvoiceItem> getItems() { return items; }
    public void setItems(List<InvoiceItem> items) { this.items = items; }
}