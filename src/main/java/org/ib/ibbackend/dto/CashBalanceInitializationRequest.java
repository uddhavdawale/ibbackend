package org.ib.ibbackend.dto;

import java.time.Instant;
import java.util.List;

public class CashBalanceInitializationRequest {
    private Instant trackingStartedAt;
    private String idempotencyKey;
    private List<OpeningBalance> openingBalances;

    public Instant getTrackingStartedAt() { return trackingStartedAt; }
    public void setTrackingStartedAt(Instant trackingStartedAt) { this.trackingStartedAt = trackingStartedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public List<OpeningBalance> getOpeningBalances() { return openingBalances; }
    public void setOpeningBalances(List<OpeningBalance> openingBalances) { this.openingBalances = openingBalances; }

    public static class OpeningBalance {
        private Long userId;
        private java.math.BigDecimal amount;
        private String note;
        private String idempotencyKey;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public java.math.BigDecimal getAmount() { return amount; }
        public void setAmount(java.math.BigDecimal amount) { this.amount = amount; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    }
}
