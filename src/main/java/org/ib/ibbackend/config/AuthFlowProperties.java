package org.ib.ibbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.auth-flow")
public class AuthFlowProperties {
    private String mfaOtpHmacSecret;
    private String frontendBaseUrl = "http://localhost:5173";
    private int passwordResetExpiryMinutes = 30;
    private int mfaChallengeExpiryMinutes = 10;

    public String getMfaOtpHmacSecret() {
        return mfaOtpHmacSecret;
    }

    public void setMfaOtpHmacSecret(String mfaOtpHmacSecret) {
        this.mfaOtpHmacSecret = mfaOtpHmacSecret;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public void setFrontendBaseUrl(String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public int getPasswordResetExpiryMinutes() {
        return passwordResetExpiryMinutes;
    }

    public void setPasswordResetExpiryMinutes(int passwordResetExpiryMinutes) {
        this.passwordResetExpiryMinutes = passwordResetExpiryMinutes;
    }

    public int getMfaChallengeExpiryMinutes() {
        return mfaChallengeExpiryMinutes;
    }

    public void setMfaChallengeExpiryMinutes(int mfaChallengeExpiryMinutes) {
        this.mfaChallengeExpiryMinutes = mfaChallengeExpiryMinutes;
    }
}
