package org.ib.ibbackend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public class LoginRequest {
    @NotBlank
    @JsonProperty("username")
    private String username;  // login username

    @JsonProperty("email")
    private String email;  // optional compatibility login identifier

    @NotBlank
    @JsonProperty("password")
    private String password;  // Raw password from React

    // Optional encrypted login envelope used by newer clients.
    @JsonProperty("encryptedPayload")
    private String encryptedPayload;

    @JsonProperty("encryptedKey")
    private String encryptedKey;

    @JsonProperty("iv")
    private String iv;

    public LoginRequest() {
    }

    // Getters/Setters
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getEncryptedPayload() { return encryptedPayload; }
    public void setEncryptedPayload(String encryptedPayload) { this.encryptedPayload = encryptedPayload; }

    public String getEncryptedKey() { return encryptedKey; }
    public void setEncryptedKey(String encryptedKey) { this.encryptedKey = encryptedKey; }

    public String getIv() { return iv; }
    public void setIv(String iv) { this.iv = iv; }
}