package org.ib.ibbackend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class CashHandoverConfirmationRequest {
    @NotNull
    @DecimalMin(value = "0.00")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal countedAmount;

    @NotBlank
    @Size(max = 100)
    private String idempotencyKey;

    @Size(max = 1000)
    private String notes;

    public BigDecimal getCountedAmount() { return countedAmount; }
    public void setCountedAmount(BigDecimal countedAmount) { this.countedAmount = countedAmount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
