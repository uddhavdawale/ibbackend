package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.LoginModel;
import org.ib.ibbackend.dto.LoginRequest;
import org.ib.ibbackend.dto.LoginResponse;
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

@RestController()
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class LoginController {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @PostMapping(path = {"/api/login", "/login"})
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        Optional<User> userOpt = findUserByLoginIdentifier(request.getUsername());

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(false, "Invalid username or password"));
        }

        User user = userOpt.get();

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(false, "Invalid username or password"));
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

        LoginResponse response = new LoginResponse(true, "Login successful");
        response.setToken(accessToken);
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setTokenType("Bearer");
        response.setUsername(user.getOwnerName());
        response.setRole(user.getRole());
        response.setEmail(user.getEmail());
        response.setUser(userPayload);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(response);
    }

    private Optional<User> findUserByLoginIdentifier(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        Optional<User> userByEmail = userRepository.findByEmail(username);
        if (userByEmail.isPresent()) {
            return userByEmail;
        }

        return userRepository.findByOwnerName(username);
    }

    @RequestMapping("/dummy")
    public String getDummyData() {
        return "This is some dummy data.";
    }

    @GetMapping("/getLoginData")
    public LoginModel getLoginData() {
        LoginModel loginModel = new LoginModel();
        loginModel.setUsername("admin@gmail.com");
        loginModel.setPassword("password");
        return loginModel;
    }
}
