package org.ib.ibbackend.dto;

public class LoginResponse {

    private boolean success;
    private String message;
    private String token;
    private String accessToken;

    private String tokenType = "Bearer";
    private String username;
    private String role;
    private String email;
    private Object user;
    private boolean requiresMfa;

    public LoginResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public Object getUser() { return user; }
    public void setUser(Object user) { this.user = user; }

    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public boolean isRequiresMfa() { return requiresMfa; }
    public void setRequiresMfa(boolean requiresMfa) { this.requiresMfa = requiresMfa; }
}