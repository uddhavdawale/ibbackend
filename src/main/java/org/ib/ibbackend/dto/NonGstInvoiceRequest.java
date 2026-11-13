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
    private BigDecimal amountPaid;
    private BigDecimal receivedAmount;
    private BigDecimal previousBalance;
    private BigDecimal balanceAmount;
    private BigDecimal currentBalance;
    private BigDecimal totalAmount;
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
        if (paidAmount != null) {
            return paidAmount;
        }
        if (amountPaid != null) {
            return amountPaid;
        }
        if (receivedAmount != null) {
            return receivedAmount;
        }
        return BigDecimal.ZERO;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public BigDecimal getAmountPaid() {
        return amountPaid == null ? getPaidAmount() : amountPaid;
    }

    public void setAmountPaid(BigDecimal amountPaid) {
        this.amountPaid = amountPaid;
    }

    public BigDecimal getReceivedAmount() {
        return receivedAmount == null ? getPaidAmount() : receivedAmount;
    }

    public void setReceivedAmount(BigDecimal receivedAmount) {
        this.receivedAmount = receivedAmount;
    }

    public BigDecimal getPreviousBalance() {
        return previousBalance == null ? BigDecimal.ZERO : previousBalance;
    }

    public void setPreviousBalance(BigDecimal previousBalance) {
        this.previousBalance = previousBalance;
    }

    public BigDecimal getBalanceAmount() {
        return balanceAmount == null ? BigDecimal.ZERO : balanceAmount;
    }

    public void setBalanceAmount(BigDecimal balanceAmount) {
        this.balanceAmount = balanceAmount;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance == null ? BigDecimal.ZERO : currentBalance;
    }

    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount == null ? BigDecimal.ZERO : totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public List<NonGstInvoiceRequestItem> getItems() {
        return items;
    }

    public void setItems(List<NonGstInvoiceRequestItem> items) {
        this.items = items;
    }
}
