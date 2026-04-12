package org.ib.ibbackend.dto;

import org.ib.ibbackend.entity.InvoiceItem;
import java.util.List;

public class InvoiceRequest {
    private String customerName;
    private String customerPhone;
    private String paymentMethod;
    private List<InvoiceItem> items;

    // ✅ Jackson needs no-arg constructor
    public InvoiceRequest() {}

    // ✅ Convenience constructor
    public InvoiceRequest(String customerName, String customerPhone,
                          String paymentMethod, List<InvoiceItem> items) {
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.paymentMethod = paymentMethod;
        this.items = items;
    }

    // ✅ Explicit getters/setters
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public List<InvoiceItem> getItems() { return items; }
    public void setItems(List<InvoiceItem> items) { this.items = items; }
}