package org.ib.ibbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mfa.firebase")
public class FirebaseMfaProperties {
    private boolean enabled = false;
    private String projectId;
    private String credentialsPath;
    private String credentialsJsonBase64;
    private boolean requiredEmailVerified = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getCredentialsPath() {
        return credentialsPath;
    }

    public void setCredentialsPath(String credentialsPath) {
        this.credentialsPath = credentialsPath;
    }

    public String getCredentialsJsonBase64() {
        return credentialsJsonBase64;
    }

    public void setCredentialsJsonBase64(String credentialsJsonBase64) {
        this.credentialsJsonBase64 = credentialsJsonBase64;
    }

    public boolean isRequiredEmailVerified() {
        return requiredEmailVerified;
    }

    public void setRequiredEmailVerified(boolean requiredEmailVerified) {
        this.requiredEmailVerified = requiredEmailVerified;
    }
}

