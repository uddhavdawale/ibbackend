package org.ib.ibbackend.login;

import org.ib.ibbackend.dto.LoginModel;
import org.ib.ibbackend.dto.LoginStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController()
@CrossOrigin(origins = {  "http://localhost:5173","http://ibbackend:8080/api/" , "http://localhost:4173","https://ibfrontendlive.vercel.app/"})
public class LoginController {
   // @PostMapping(path = "/login")
    public LoginStatus login1(@RequestBody() LoginModel loginModel) {
        // Implement your login logic here
        LoginStatus loginStatus = new LoginStatus(false, "Invalid username or password.");
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
    @PostMapping("/api/login")
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
    }
}
