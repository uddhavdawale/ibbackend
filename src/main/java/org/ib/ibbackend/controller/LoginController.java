package org.ib.ibbackend.controller;

import org.ib.ibbackend.dto.LoginModel;
import org.ib.ibbackend.dto.LoginRequest;
import org.ib.ibbackend.dto.LoginResponse;
import org.ib.ibbackend.dto.LoginResponse;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController()
@CrossOrigin(origins = {  "http://localhost:5173","http://ibbackend:8080/api/" , "http://localhost:4173","https://ibfrontendlive.vercel.app/","http://localhost:8080/"})
public class LoginController {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

   // @PostMapping(path = "/login")
    public LoginResponse login1(@RequestBody() LoginModel loginModel) {
        // Implement your login logic here
        LoginResponse loginStatus = new LoginResponse(false, "Invalid username or password.");
        if (loginModel.getUsername().equals("admin@gmail.com") && loginModel.getPassword().equals("password")) {
            loginStatus.setMessage( "Login valid");
            loginStatus.setSuccess(true);
            return loginStatus;
        } else {
            return loginStatus;
        }
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
  /*  @PostMapping("/api/login")
        public ResponseEntity<Map<String, Object>> login(@RequestBody() LoginModel loginReg) {
        Map<String, Object> response = new HashMap<>();

        // Validate credentials
        if ("admin".equals(loginReg.getUsername()) && "admin@123".equals(loginReg.getPassword())) {
            response.put("success", true);
            response.put("username", loginReg.getUsername());
            response.put("email", "admin@example.com");
            response.put("token", "your-jwt-token-here");
            return ResponseEntity.ok(response);
        } else {
            response.put("success", false);
            response.put("message", "Invalid credentials");
            return ResponseEntity.badRequest().body(response);
        }
    }*/
    // 👈 NEW LOGIN
    @PostMapping("/api/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        try {
            // Find user by ownerName (username)

            Optional<User> userOpt = userRepository.findByOwnerName(request.getUsername());

            if (userOpt.isEmpty()) {
                return ResponseEntity.ok(new LoginResponse(false, "User not found"));
            }

            User user = userOpt.get();


            // Check password (frontend hash + backend BCrypt)
            if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                return ResponseEntity.ok(new LoginResponse(false, "Invalid password"));
            }

            // LOGIN SUCCESS
            LoginResponse response = new LoginResponse(true, "Login successful");
            response.setUsername(user.getOwnerName());
            response.setRole(user.getRole());
            response.setEmail(user.getEmail());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.ok(new LoginResponse(false, "Server error"));
        }
    }
}
