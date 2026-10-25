package org.ib.ibbackend.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class NonGstInvoiceRequest {
    private String customerName;
    private String customerPhone;
    private String paymentMethod;
    private String paymentStatus;
    private String status;
    private String billType;
    private Boolean isGstInvoice;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    private List<NonGstInvoiceRequestItem> items = new ArrayList<>();

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

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getBillType() {
        return billType;
    }

    public void setBillType(String billType) {
        this.billType = billType;
    }

    public Boolean getIsGstInvoice() {
        return isGstInvoice;
    }

    public void setIsGstInvoice(Boolean gstInvoice) {
        isGstInvoice = gstInvoice;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount == null ? BigDecimal.ZERO : paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public List<NonGstInvoiceRequestItem> getItems() {
        return items;
    }

    public void setItems(List<NonGstInvoiceRequestItem> items) {
        this.items = items;
    }
}
