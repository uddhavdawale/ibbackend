package org.ib.ibbackend.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.ib.ibbackend.dto.*;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.ib.ibbackend.service.JwtService;
import org.ib.ibbackend.service.RefreshTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
public class AuthController {

    @Autowired
    private UserRepository userRepo;

    @Autowired
    private BCryptPasswordEncoder bcrypt;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        User user = findUserByLoginIdentifier(req.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!bcrypt.matches(req.getPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(401).build();
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        refreshTokenService.storeToken(user, refreshToken);

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(7))
                .build();

        Map<String, Object> userPayload = new HashMap<>();
        userPayload.put("id", user.getId());
        userPayload.put("username", user.getOwnerName());
        userPayload.put("name", user.getOwnerName());
        userPayload.put("email", user.getEmail());
        userPayload.put("role", user.getRole());

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Login successful");
        response.put("accessToken", accessToken);
        response.put("refreshToken", refreshToken);
        response.put("tokenType", "Bearer");
        response.put("user", userPayload);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody(required = false) RefreshTokenRequest request,
                                         @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken,
                                         HttpServletRequest httpRequest) {
        String incomingRefreshToken = null;

        if (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank()) {
            incomingRefreshToken = request.getRefreshToken();
        } else if (cookieRefreshToken != null && !cookieRefreshToken.isBlank()) {
            incomingRefreshToken = cookieRefreshToken;
        }

        if (incomingRefreshToken == null || incomingRefreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token missing");
        }

        try {
            User user = findUserByLoginIdentifier(request != null ? request.getUsername() : null)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            if (!refreshTokenService.isValidForUser(user, incomingRefreshToken)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or expired refresh token");
            }

            String newAccessToken = jwtService.generateAccessToken(user);
            String newRefreshToken = jwtService.generateRefreshToken(user);
            refreshTokenService.rotateToken(user, incomingRefreshToken, newRefreshToken);

            ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", newRefreshToken)
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(Duration.ofDays(7))
                    .build();

            Map<String, Object> userPayload = new HashMap<>();
            userPayload.put("id", user.getId());
            userPayload.put("username", user.getOwnerName());
            userPayload.put("name", user.getOwnerName());
            userPayload.put("email", user.getEmail());
            userPayload.put("role", user.getRole());

            RefreshTokenResponse response = new RefreshTokenResponse(true, "Token refreshed successfully");
            response.setAccessToken(newAccessToken);
            response.setRefreshToken(newRefreshToken);
            response.setUserName(user.getOwnerName());
            response.setUser(userPayload);

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                    .body(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid refresh token or user login");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody(required = false) RefreshTokenRequest request,
                                   @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken) {
        String tokenToRevoke = null;

        if (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank()) {
            tokenToRevoke = request.getRefreshToken();
        } else if (cookieRefreshToken != null && !cookieRefreshToken.isBlank()) {
            tokenToRevoke = cookieRefreshToken;
        }

        if (tokenToRevoke != null && !tokenToRevoke.isBlank()) {
            try {
                String username = jwtService.extractRefreshClaims(tokenToRevoke).getSubject();
                User user = findUserByLoginIdentifier(username).orElse(null);
                if (user != null) {
                    refreshTokenService.revokeAllActiveTokensForUser(user);
                } else {
                    refreshTokenService.revokeToken(tokenToRevoke);
                }
            } catch (Exception e) {
                refreshTokenService.revokeToken(tokenToRevoke);
            }
        }

        ResponseCookie clearedCookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearedCookie.toString())
                .body(new ApiResponse(true, "Logged out successfully"));
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse> signup(@RequestBody SignupRequest request) {
        try {
            if (userRepo.existsByEmail(request.getEmail())) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Email already exists"));
            }

            String userPassword = bcrypt.encode(request.getPasswordHash());

            User user = new User();
            user.setBusinessName(request.getBusinessName().trim());
            user.setOwnerName(request.getOwnerName().trim());
            user.setEmail(request.getEmail().trim());
            user.setPasswordHash(userPassword);
            user.setRole(request.getRole());
            user.setPhone(request.getPhone());
            user.setGstin(request.getGstin());
            user.setPan(request.getPan());
            user.setAddress(request.getAddress());
            user.setCity(request.getCity());
            user.setPincode(request.getPincode());

            userRepo.save(user);

            return ResponseEntity.ok(new ApiResponse(true, "Account created successfully"));

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ApiResponse(false, "Server error: " + e.getMessage()));
        }
    }

    private Optional<User> findUserByLoginIdentifier(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        Optional<User> userByEmail = userRepo.findByEmail(username);
        if (userByEmail.isPresent()) {
            return userByEmail;
        }

        return userRepo.findByOwnerName(username);
    }
}