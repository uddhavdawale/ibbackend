package org.ib.ibbackend.dto;

import java.math.BigDecimal;

public class HsnTaxRateRequest {
    private String hsnCode;
    private BigDecimal gstRate;

    public String getHsnCode() {
        return hsnCode;
    }

    public void setHsnCode(String hsnCode) {
        this.hsnCode = hsnCode;
    }

    public BigDecimal getGstRate() {
        return gstRate;
    }

    public void setGstRate(BigDecimal gstRate) {
        this.gstRate = gstRate;
    }
}
