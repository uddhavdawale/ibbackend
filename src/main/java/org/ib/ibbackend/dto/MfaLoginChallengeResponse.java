package org.ib.ibbackend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MfaLoginChallengeResponse extends LoginResponse {
    private final boolean mfaRequired;
    private final Map<String, Object> mfaChallenge;

    public MfaLoginChallengeResponse(Map<String, Object> mfaChallenge) {
        super(false, "MFA verification required");
        setTokenType(null);
        setRequiresMfa(true);
        this.mfaRequired = true;
        this.mfaChallenge = mfaChallenge;
    }

    public boolean isMfaRequired() {
        return mfaRequired;
    }

    public Map<String, Object> getMfaChallenge() {
        return mfaChallenge;
    }
}
