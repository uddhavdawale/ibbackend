package org.ib.ibbackend.dto;

import org.ib.ibbackend.entity.InvoiceItem;
import com.fasterxml.jackson.annotation.JsonAlias;
import java.math.BigDecimal;
import java.util.List;

public class InvoiceRequest {
    private String customerName;
    private String customerPhone;
    private String customerAddress;
    private String receiptAddress;
    private String customerState;
    private String paymentMethod;
    private String status;
    private String paymentStatus;
    private BigDecimal paidAmount;
    private BigDecimal amountPaid;
    private BigDecimal receivedAmount;
    private BigDecimal previousBalance;
    private BigDecimal balanceAmount;
    private BigDecimal currentBalance;
    private BigDecimal totalAmount;
    private String billType;
    private Boolean isGstInvoice;
    private String issuerType;
    private String taxPricingMode;
    @JsonAlias("placeOfSupplyStateCode")
    private String placeOfSupplyState;
    private BigDecimal subtotal;
    private BigDecimal gstAmount;
    private BigDecimal grandTotal;
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
        this.paidAmount = paidAmount;
        this.status = status;
        this.items = items;
    }

    // ✅ Explicit getters/setters
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getCustomerAddress() {
        return customerAddress != null ? customerAddress : receiptAddress;
    }
    public void setCustomerAddress(String customerAddress) { this.customerAddress = customerAddress; }

    public String getReceiptAddress() { return receiptAddress != null ? receiptAddress : customerAddress; }
    public void setReceiptAddress(String receiptAddress) { this.receiptAddress = receiptAddress; }

    public String getCustomerState() { return customerState; }
    public void setCustomerState(String customerState) { this.customerState = customerState; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }

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
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public BigDecimal getAmountPaid() { return amountPaid == null ? getPaidAmount() : amountPaid; }
    public void setAmountPaid(BigDecimal amountPaid) { this.amountPaid = amountPaid; }

    public BigDecimal getReceivedAmount() { return receivedAmount == null ? getPaidAmount() : receivedAmount; }
    public void setReceivedAmount(BigDecimal receivedAmount) { this.receivedAmount = receivedAmount; }

    public BigDecimal getPreviousBalance() { return previousBalance == null ? BigDecimal.ZERO : previousBalance; }
    public void setPreviousBalance(BigDecimal previousBalance) { this.previousBalance = previousBalance; }

    public BigDecimal getBalanceAmount() { return balanceAmount == null ? BigDecimal.ZERO : balanceAmount; }
    public void setBalanceAmount(BigDecimal balanceAmount) { this.balanceAmount = balanceAmount; }

    public BigDecimal getCurrentBalance() { return currentBalance == null ? BigDecimal.ZERO : currentBalance; }
    public void setCurrentBalance(BigDecimal currentBalance) { this.currentBalance = currentBalance; }

    public BigDecimal getTotalAmount() { return totalAmount == null ? BigDecimal.ZERO : totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getBillType() { return billType; }
    public void setBillType(String billType) { this.billType = billType; }

    public Boolean getIsGstInvoice() { return isGstInvoice == null ? Boolean.TRUE : isGstInvoice; }
    public void setIsGstInvoice(Boolean gstInvoice) { isGstInvoice = gstInvoice; }

    public String getIssuerType() { return issuerType; }
    public void setIssuerType(String issuerType) { this.issuerType = issuerType; }

    public String getTaxPricingMode() { return taxPricingMode; }
    public void setTaxPricingMode(String taxPricingMode) { this.taxPricingMode = taxPricingMode; }

    public String getPlaceOfSupplyState() { return placeOfSupplyState; }
    public void setPlaceOfSupplyState(String placeOfSupplyState) { this.placeOfSupplyState = placeOfSupplyState; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getGstAmount() { return gstAmount; }
    public void setGstAmount(BigDecimal gstAmount) { this.gstAmount = gstAmount; }

    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }

    public List<InvoiceItem> getItems() { return items; }
    public void setItems(List<InvoiceItem> items) { this.items = items; }
}