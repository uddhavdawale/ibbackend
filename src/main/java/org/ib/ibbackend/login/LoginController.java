package org.ib.ibbackend.login;

import org.ib.ibbackend.dto.LoginModel;
import org.ib.ibbackend.dto.LoginStatus;
import org.springframework.web.bind.annotation.*;

@RestController()
@CrossOrigin(origins = {  "http://localhost:5173","http://ibbackend:8080/api/" })
public class LoginController {
    @PostMapping(path = "/login")
    public LoginStatus login(@RequestBody() LoginModel loginModel) {
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
}
