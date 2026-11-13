package org.ib.ibbackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

 enum Role {
    USER, STORE_ADMIN
}

@Entity
@Table(name = "users", uniqueConstraints = {
    @UniqueConstraint(columnNames = "email"),
    @UniqueConstraint(columnNames = "username")
})
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String businessName;

    @Column(nullable = false)
    private String ownerName;

    @Column(unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;  // Secure password hash (Argon2id)

    private String phone;
    private String gstin;  // Retailer
    private String pan;    // CA Partner


    @Column(name = "role")
    private String role= "STORE_ADMIN";   // retailer/partner

    private String address;
    private String city;
    private String pincode;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "disabled", nullable = true)
    private Boolean disabled = Boolean.FALSE;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false)
    private RetailerStatus approvalStatus = RetailerStatus.APPROVED;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "last_admin_action_at")
    private LocalDateTime lastAdminActionAt;

    @Column(name = "admin_note")
    private String adminNote;

    @Column(name = "email_verified")
    private boolean emailVerified = false;

    @ManyToOne
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    // Default constructor
    public User() {}

    // Getters & Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }


    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }

    public String getPan() { return pan; }
    public void setPan(String pan) { this.pan = pan; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public Long getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Long deletedBy) { this.deletedBy = deletedBy; }

    public boolean isDisabled() {
        return Boolean.TRUE.equals(disabled)
                || approvalStatus == RetailerStatus.PENDING_EMAIL_VERIFICATION
                || approvalStatus == RetailerStatus.PENDING_APPROVAL
                || approvalStatus == RetailerStatus.MORE_INFO_REQUIRED
                || approvalStatus == RetailerStatus.REJECTED
                || approvalStatus == RetailerStatus.BLOCKED
                || approvalStatus == RetailerStatus.DISABLED
                || approvalStatus == RetailerStatus.DELETED
                || approvalStatus == RetailerStatus.SUSPENDED;
    }
    public void setDisabled(boolean disabled) { this.disabled = disabled; }

    public RetailerStatus getApprovalStatus() {
        return approvalStatus == null ? RetailerStatus.APPROVED : approvalStatus;
    }

    public void setApprovalStatus(RetailerStatus approvalStatus) {
        this.approvalStatus = approvalStatus == null ? RetailerStatus.APPROVED : approvalStatus;
    }

    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

    public LocalDateTime getLastAdminActionAt() { return lastAdminActionAt; }
    public void setLastAdminActionAt(LocalDateTime lastAdminActionAt) { this.lastAdminActionAt = lastAdminActionAt; }

    public String getAdminNote() { return adminNote; }
    public void setAdminNote(String adminNote) { this.adminNote = adminNote; }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public Tenant getTenant() {
        return tenant;
    }
}