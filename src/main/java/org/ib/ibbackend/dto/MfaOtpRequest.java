package org.ib.ibbackend.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public class MfaOtpRequest {
    private String username;
    @Size(max = 6)
    @Pattern(regexp = "\\d{6}")
    private String otp;
    @Size(max = 36)
    private String challengeId;
    private String firebaseIdToken;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public void setChallengeId(String challengeId) {
        this.challengeId = challengeId;
    }

    public String getOtp() {
        return otp;
    }

    public void setOtp(String otp) {
        this.otp = otp;
    }

    public String getFirebaseIdToken() {
        return firebaseIdToken;
    }

    public void setFirebaseIdToken(String firebaseIdToken) {
        this.firebaseIdToken = firebaseIdToken;
    }
}
