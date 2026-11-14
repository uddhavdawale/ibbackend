package org.ib.ibbackend.dto;

import java.util.Map;

public class RefreshTokenResponse {
    private String userName;
    private String accessToken;
    private boolean success;
    private String message;
    private Map<String, Object> user;

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public RefreshTokenResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
    public String getAccessToken() {
        return accessToken;
    }

    public RefreshTokenResponse(String userName) {
        this.userName = userName;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public Map<String, Object> getUser() {
        return user;
    }

    public void setUser(Map<String, Object> user) {
        this.user = user;
    }

    public RefreshTokenResponse() {
    }
}

