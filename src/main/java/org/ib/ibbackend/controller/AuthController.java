package org.ib.ibbackend.controller;


import org.ib.ibbackend.dto.SignupRequest;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")  // 👈 React dev
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;  // 👈 Auto-config

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse> signup(@RequestBody SignupRequest request) {
        try {
            // Check duplicate email
            if (userRepository.existsByEmail(request.getEmail())) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Email already exists"));
            }

            // Double encrypt (frontend + backend)
            String finalPassword = passwordEncoder.encode(request.getPasswordHash());

            // Create user
            User user = new User();
            user.setBusinessName(request.getBusinessName());
            user.setOwnerName(request.getOwnerName());
            user.setEmail(request.getEmail());
            user.setPasswordHash(finalPassword);
            user.setRole(request.getRole());
            user.setPhone(request.getPhone());
            user.setGstin(request.getGstin());
            user.setPan(request.getPan());
            user.setAddress(request.getAddress());
            user.setCity(request.getCity());
            user.setPincode(request.getPincode());

            userRepository.save(user);

            return ResponseEntity.ok(new ApiResponse(true, "Account created successfully"));

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ApiResponse(false, "Server error: " + e.getMessage()));
        }
    }
}