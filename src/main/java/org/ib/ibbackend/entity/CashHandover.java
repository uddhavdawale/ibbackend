package org.ib.ibbackend.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "cash_handovers")
public class CashHandover {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_user_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_user_id", nullable = false)
    private User recipient;

    @Column(name = "declared_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal declaredAmount;

    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod = "CASH";

    @Column(name = "payment_proof_sha256", length = 64)
    private String paymentProofSha256;

    @Column(name = "receiver_counted_amount", precision = 12, scale = 2)
    private BigDecimal receiverCountedAmount;

    @Column(name = "resolved_amount", precision = 12, scale = 2)
    private BigDecimal resolvedAmount;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "receiver_confirmation_key", length = 100)
    private String receiverConfirmationKey;

    @Column(name = "resolution_idempotency_key", length = 100)
    private String resolutionIdempotencyKey;

    @Column(length = 1000)
    private String notes;

    @Column(name = "recipient_notes", length = 1000)
    private String recipientNotes;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "sender_confirmed_at", nullable = false)
    private Instant senderConfirmedAt;

    @Column(name = "recipient_confirmed_at")
    private Instant recipientConfirmedAt;

    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (senderConfirmedAt == null) senderConfirmedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public User getSender() { return sender; }
    public void setSender(User sender) { this.sender = sender; }
    public User getRecipient() { return recipient; }
    public void setRecipient(User recipient) { this.recipient = recipient; }
    public BigDecimal getDeclaredAmount() { return declaredAmount; }
    public void setDeclaredAmount(BigDecimal declaredAmount) { this.declaredAmount = declaredAmount; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getPaymentProofSha256() { return paymentProofSha256; }
    public void setPaymentProofSha256(String paymentProofSha256) { this.paymentProofSha256 = paymentProofSha256; }
    public BigDecimal getReceiverCountedAmount() { return receiverCountedAmount; }
    public void setReceiverCountedAmount(BigDecimal receiverCountedAmount) { this.receiverCountedAmount = receiverCountedAmount; }
    public BigDecimal getResolvedAmount() { return resolvedAmount; }
    public void setResolvedAmount(BigDecimal resolvedAmount) { this.resolvedAmount = resolvedAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getReceiverConfirmationKey() { return receiverConfirmationKey; }
    public void setReceiverConfirmationKey(String receiverConfirmationKey) { this.receiverConfirmationKey = receiverConfirmationKey; }
    public String getResolutionIdempotencyKey() { return resolutionIdempotencyKey; }
    public void setResolutionIdempotencyKey(String resolutionIdempotencyKey) { this.resolutionIdempotencyKey = resolutionIdempotencyKey; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getRecipientNotes() { return recipientNotes; }
    public void setRecipientNotes(String recipientNotes) { this.recipientNotes = recipientNotes; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
    public Instant getSenderConfirmedAt() { return senderConfirmedAt; }
    public void setSenderConfirmedAt(Instant senderConfirmedAt) { this.senderConfirmedAt = senderConfirmedAt; }
    public Instant getRecipientConfirmedAt() { return recipientConfirmedAt; }
    public void setRecipientConfirmedAt(Instant recipientConfirmedAt) { this.recipientConfirmedAt = recipientConfirmedAt; }
    public Long getResolvedByUserId() { return resolvedByUserId; }
    public void setResolvedByUserId(Long resolvedByUserId) { this.resolvedByUserId = resolvedByUserId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
