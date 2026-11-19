package org.ib.ibbackend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cash_collection_payment_proofs")
public class CashCollectionPaymentProof {
    @Id
    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "encrypted_content", nullable = false, columnDefinition = "BYTEA")
    private byte[] encryptedContent;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public byte[] getEncryptedContent() { return encryptedContent; }
    public void setEncryptedContent(byte[] encryptedContent) { this.encryptedContent = encryptedContent; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
}
