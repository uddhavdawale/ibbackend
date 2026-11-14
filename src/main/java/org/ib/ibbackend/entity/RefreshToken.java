package org.ib.ibbackend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_tokens",
        uniqueConstraints = @UniqueConstraint(columnNames = "token_hash"))
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "replaced_by_token_hash")
    private String replacedByTokenHash;

    @Column(name = "family_id", nullable = false, length = 64)
    private String familyId;

    @Column(name = "replacement_token_ciphertext", columnDefinition = "TEXT")
    private String replacementTokenCiphertext;

    @Column(name = "replacement_grace_until")
    private LocalDateTime replacementGraceUntil;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getReplacedByTokenHash() {
        return replacedByTokenHash;
    }

    public void setReplacedByTokenHash(String replacedByTokenHash) {
        this.replacedByTokenHash = replacedByTokenHash;
    }

    public String getFamilyId() {
        return familyId;
    }

    public void setFamilyId(String familyId) {
        this.familyId = familyId;
    }

    public String getReplacementTokenCiphertext() {
        return replacementTokenCiphertext;
    }

    public void setReplacementTokenCiphertext(String replacementTokenCiphertext) {
        this.replacementTokenCiphertext = replacementTokenCiphertext;
    }

    public LocalDateTime getReplacementGraceUntil() {
        return replacementGraceUntil;
    }

    public void setReplacementGraceUntil(LocalDateTime replacementGraceUntil) {
        this.replacementGraceUntil = replacementGraceUntil;
    }
}
