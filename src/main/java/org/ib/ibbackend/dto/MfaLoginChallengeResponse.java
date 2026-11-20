package org.ib.ibbackend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MfaLoginChallengeResponse extends LoginResponse {
    private final boolean mfaRequired;
    private final Map<String, Object> mfaChallenge;
    private final String challengeId;
    private final String maskedDestination;
    private final String expiresAt;

    public MfaLoginChallengeResponse(Map<String, Object> mfaChallenge) {
        super(false, "MFA verification required");
        setTokenType(null);
        setRequiresMfa(true);
        this.mfaRequired = true;
        this.mfaChallenge = mfaChallenge;
        this.challengeId = valueAsString(mfaChallenge, "challengeId");
        this.maskedDestination = valueAsString(mfaChallenge, "maskedDestination");
        this.expiresAt = valueAsString(mfaChallenge, "expiresAt");
    }

    public boolean isMfaRequired() {
        return mfaRequired;
    }

    public Map<String, Object> getMfaChallenge() {
        return mfaChallenge;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public String getMaskedDestination() {
        return maskedDestination;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    private static String valueAsString(Map<String, Object> challenge, String key) {
        Object value = challenge == null ? null : challenge.get(key);
        return value == null ? null : value.toString();
    }
}
