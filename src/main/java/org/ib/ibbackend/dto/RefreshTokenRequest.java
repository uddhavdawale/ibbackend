package org.ib.ibbackend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public class RefreshTokenRequest {
    @NotBlank
    @JsonProperty("username")
    private String username;  // ownerName from users table

    @NotBlank
    @JsonProperty("refreshToken")
    private String refreshToken;  // Raw password from React

    public RefreshTokenRequest() {
    }

    // Getters/Setters
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

}
