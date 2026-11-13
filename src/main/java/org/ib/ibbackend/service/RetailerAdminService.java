package org.ib.ibbackend.service;

import org.ib.ibbackend.entity.RetailerStatus;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.security.UserRolePolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

@Service
public class RetailerAdminService {

    private final UserRepository userRepository;

    public RetailerAdminService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Page<User> listRetailersForTenant(int tenantId, String search, String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id"));

        String normalizedSearch = search == null ? null : search.trim();
        List<User> users = userRepository.findTenantUsersForManagement( normalizedSearch, true, pageable)
                .getContent()
                .stream()
                .filter(this::isRetailerManagedUser)
                .filter(user -> matchesStatusFilter(user, status))
                .toList();

        return new PageImpl<>(users, pageable, users.size());
    }

    public Page<User> listRetailersForPlatformAdmin(String search, String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size <= 0 ? 20 : size, 100));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id"));

        String normalizedSearch = search == null ? null : search.trim();
        String normalizedStatus = status == null || status.isBlank()
                ? null
                : RetailerStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();

        return userRepository.findRetailersForManagement(normalizedStatus, normalizedSearch, pageable);
    }

    public Optional<User> findRetailerForTenant(Long userId, int tenantId) {
        return userRepository.findByIdAndTenant_Id(userId, tenantId);
    }

    public User approveRetailer(Long userId, String adminNote, String actorEmail) {
        User actor = resolveActor(actorEmail);
        enforceAdminGuard(actor, userId, "APPROVED", adminNote);
        User target = findRequiredUser(userId, actor);
        return applyStatus(target, RetailerStatus.APPROVED, adminNote, actor);
    }

    public User rejectRetailer(Long userId, String adminNote, String actorEmail) {
        User actor = resolveActor(actorEmail);
        enforceAdminGuard(actor, userId, "REJECTED", adminNote);
        User target = findRequiredUser(userId, actor);
        return applyStatus(target, RetailerStatus.REJECTED, adminNote, actor);
    }

    public User blockRetailer(Long userId, String adminNote, String actorEmail) {
        User actor = resolveActor(actorEmail);
        enforceAdminGuard(actor, userId, "BLOCKED", adminNote);
        User target = findRequiredUser(userId, actor);
        return applyStatus(target, RetailerStatus.BLOCKED, adminNote, actor);
    }

    public User unblockRetailer(Long userId, String adminNote, String actorEmail) {
        User actor = resolveActor(actorEmail);
        enforceAdminGuard(actor, userId, "APPROVED", adminNote);
        User target = findRequiredUser(userId, actor);
        return applyStatus(target, RetailerStatus.APPROVED, adminNote, actor);
    }

    public User disableRetailer(Long userId, String adminNote, String actorEmail) {
        User actor = resolveActor(actorEmail);
        enforceAdminGuard(actor, userId, "DISABLED", adminNote);
        User target = findRequiredUser(userId, actor);
        return applyStatus(target, RetailerStatus.DISABLED, adminNote, actor);
    }

    public User requestMoreInfo(Long userId, String adminNote, String actorEmail) {
        User actor = resolveActor(actorEmail);
        enforceAdminGuard(actor, userId, "MORE_INFO_REQUIRED", adminNote);
        User target = findRequiredUser(userId, actor);
        return applyStatus(target, RetailerStatus.MORE_INFO_REQUIRED, adminNote, actor);
    }

    public User enableRetailer(Long userId, String adminNote, String actorEmail) {
        User actor = resolveActor(actorEmail);
        enforceAdminGuard(actor, userId, "APPROVED", adminNote);
        User target = findRequiredUser(userId, actor);
        return applyStatus(target, RetailerStatus.APPROVED, adminNote, actor);
    }

    public User softDeleteRetailer(Long userId, String adminNote, String actorEmail) {
        User actor = resolveActor(actorEmail);
        enforceAdminGuard(actor, userId, "DELETED", adminNote);
        User target = findRequiredUser(userId, actor);
        target.setDeletedAt(LocalDateTime.now());
        target.setDeletedBy(actor.getId());
        target.setApprovalStatus(RetailerStatus.DELETED);
        target.setDisabled(true);
        target.setLastAdminActionAt(LocalDateTime.now());
        target.setAdminNote(trimToNull(adminNote));
        return userRepository.save(target);
    }

    public void enforceAdminGuard(User actor, Long targetUserId, String requestedStatus, String note) {
        if (actor == null) {
            throw new SecurityException("Authenticated admin user is required");
        }
        if (!UserRolePolicy.canApproveRetailers(actor.getRole())) {
            throw new SecurityException("Only system admin roles can manage retailers");
        }
        if (Objects.equals(actor.getId(), targetUserId)) {
            throw new SecurityException("A system admin cannot change their own retailer status");
        }
        if (requestedStatus == null || requestedStatus.isBlank()) {
            throw new IllegalArgumentException("Status is required");
        }
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("Admin note must be 500 characters or fewer");
        }
    }

    public User updateRetailerStatus(User actor, Long userId, String statusName, String note) {
        RetailerStatus targetStatus = RetailerStatus.valueOf(statusName.trim().toUpperCase());
        switch (targetStatus) {
            case APPROVED -> { return approveRetailer(userId, note, actor.getEmail()); }
            case REJECTED -> { return rejectRetailer(userId, note, actor.getEmail()); }
            case MORE_INFO_REQUIRED -> { return requestMoreInfo(userId, note, actor.getEmail()); }
            case BLOCKED -> { return blockRetailer(userId, note, actor.getEmail()); }
            case DISABLED -> { return disableRetailer(userId, note, actor.getEmail()); }
            case DELETED -> { return softDeleteRetailer(userId, note, actor.getEmail()); }
            default -> throw new IllegalArgumentException("Unsupported retailer status: " + statusName);
        }
    }

    private User applyStatus(User target, RetailerStatus status, String adminNote, User actor) {
        if (target == null) {
            throw new IllegalArgumentException("Retailer not found");
        }

        if (status == RetailerStatus.APPROVED) {
            target.setDisabled(false);
            target.setApprovalStatus(RetailerStatus.APPROVED);
            if (target.getApprovedAt() == null) {
                target.setApprovedAt(LocalDateTime.now());
            }
        } else if (status == RetailerStatus.REJECTED) {
            target.setApprovalStatus(RetailerStatus.REJECTED);
            target.setDisabled(true);
        } else if (status == RetailerStatus.MORE_INFO_REQUIRED) {
            target.setApprovalStatus(RetailerStatus.MORE_INFO_REQUIRED);
            target.setDisabled(true);
        } else if (status == RetailerStatus.BLOCKED) {
            target.setApprovalStatus(RetailerStatus.BLOCKED);
            target.setDisabled(true);
        } else if (status == RetailerStatus.DISABLED) {
            target.setApprovalStatus(RetailerStatus.DISABLED);
            target.setDisabled(true);
        } else {
            target.setApprovalStatus(status);
            target.setDisabled(status == RetailerStatus.DELETED || status == RetailerStatus.BLOCKED || status == RetailerStatus.DISABLED || status == RetailerStatus.MORE_INFO_REQUIRED || status == RetailerStatus.REJECTED);
        }

        target.setLastAdminActionAt(LocalDateTime.now());
        target.setAdminNote(trimToNull(adminNote));
        target.setDeletedBy(actor != null ? actor.getId() : null);

        return userRepository.save(target);
    }

    private boolean isRetailerManagedUser(User user) {
        if (user == null) {
            return false;
        }
        if (user.getDeletedAt() != null) {
            return false;
        }
        return UserRolePolicy.isRetailerRole(user.getRole()) || user.getRole() == null || user.getRole().isBlank();
    }

    private boolean matchesStatusFilter(User user, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        RetailerStatus requested = RetailerStatus.valueOf(status.trim().toUpperCase());
        return user.getApprovalStatus() == requested;
    }

    private User resolveActor(String actorEmail) {
        if (actorEmail == null || actorEmail.isBlank()) {
            throw new SecurityException("Admin actor email is required");
        }
        return userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new SecurityException("Authenticated admin user not found"));
    }

    private User findRequiredUser(Long userId, User actor) {
        if (userId == null) {
            throw new IllegalArgumentException("Retailer id is required");
        }
        User target = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("Retailer not found"));
       /*
        if (actor.getTenant() != null && target.getTenant() != null
                && !Objects.equals(actor.getTenant().getId(), target.getTenant().getId())) {
            throw new SecurityException("Retailer does not belong to your tenant");
        }*/
        return target;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
