package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getName();

        if (email == null || email.isBlank()) {
            throw new AccessDeniedException("Access denied");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", user.getId());
        payload.put("email", user.getEmail());
        payload.put("businessName", user.getBusinessName());
        payload.put("ownerName", user.getOwnerName());
        payload.put("phone", user.getPhone());
        payload.put("role", user.getRole());
        payload.put("gstin", user.getGstin());
        payload.put("pan", user.getPan());
        payload.put("address", user.getAddress());
        payload.put("city", user.getCity());
        payload.put("pincode", user.getPincode());
        payload.put("tenantId", user.getTenant() != null ? user.getTenant().getId() : null);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("user", payload);
        return ResponseEntity.ok(response);
    }
}
