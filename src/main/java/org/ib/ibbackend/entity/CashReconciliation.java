package org.ib.ibbackend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "cash_reconciliations")
public class CashReconciliation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_user_id", nullable = false)
    private User staffUser;

    @Column(name = "reconciliation_date", nullable = false)
    private LocalDate reconciliationDate;

    @Column(name = "opening_float", nullable = false, precision = 12, scale = 2)
    private BigDecimal openingFloat;

    @Column(name = "system_cash_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal systemCashTotal;

    @Column(name = "expected_cash", nullable = false, precision = 12, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "cash_refunds", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashRefunds = BigDecimal.ZERO;

    @Column(name = "incoming_handovers", nullable = false, precision = 12, scale = 2)
    private BigDecimal incomingHandovers = BigDecimal.ZERO;

    @Column(name = "outgoing_handovers", nullable = false, precision = 12, scale = 2)
    private BigDecimal outgoingHandovers = BigDecimal.ZERO;

    @Column(name = "physical_cash_counted", nullable = false, precision = 12, scale = 2)
    private BigDecimal physicalCashCounted;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal difference;

    @Column(length = 1000)
    private String remarks;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public User getStaffUser() { return staffUser; }
    public void setStaffUser(User staffUser) { this.staffUser = staffUser; }
    public LocalDate getReconciliationDate() { return reconciliationDate; }
    public void setReconciliationDate(LocalDate reconciliationDate) { this.reconciliationDate = reconciliationDate; }
    public BigDecimal getOpeningFloat() { return openingFloat; }
    public void setOpeningFloat(BigDecimal openingFloat) { this.openingFloat = openingFloat; }
    public BigDecimal getSystemCashTotal() { return systemCashTotal; }
    public void setSystemCashTotal(BigDecimal systemCashTotal) { this.systemCashTotal = systemCashTotal; }
    public BigDecimal getExpectedCash() { return expectedCash; }
    public void setExpectedCash(BigDecimal expectedCash) { this.expectedCash = expectedCash; }
    public BigDecimal getCashRefunds() { return cashRefunds; }
    public void setCashRefunds(BigDecimal cashRefunds) { this.cashRefunds = cashRefunds; }
    public BigDecimal getIncomingHandovers() { return incomingHandovers; }
    public void setIncomingHandovers(BigDecimal incomingHandovers) { this.incomingHandovers = incomingHandovers; }
    public BigDecimal getOutgoingHandovers() { return outgoingHandovers; }
    public void setOutgoingHandovers(BigDecimal outgoingHandovers) { this.outgoingHandovers = outgoingHandovers; }
    public BigDecimal getPhysicalCashCounted() { return physicalCashCounted; }
    public void setPhysicalCashCounted(BigDecimal physicalCashCounted) { this.physicalCashCounted = physicalCashCounted; }
    public BigDecimal getDifference() { return difference; }
    public void setDifference(BigDecimal difference) { this.difference = difference; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
    public Instant getCreatedAt() { return createdAt; }
}
