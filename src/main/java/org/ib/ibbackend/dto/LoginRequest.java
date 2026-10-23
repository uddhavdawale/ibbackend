package org.ib.ibbackend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public class LoginRequest {
    @NotBlank
    @JsonProperty("username")
    private String username;  // ownerName from users table

    @NotBlank
    @JsonProperty("password")
    private String password;  // Raw password from React

    public LoginRequest() {
    }

    // Getters/Setters
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}