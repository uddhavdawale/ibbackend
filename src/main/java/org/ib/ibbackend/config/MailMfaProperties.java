package org.ib.ibbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.mfa.mail")
public class MailMfaProperties {
    private boolean enabled = false;
    private int otpLength = 6;
    private int expiryMinutes = 5;
    private String subject = "Your verification code";
    private String from = "noreply@localhost";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getOtpLength() {
        return 6;
    }

    public void setOtpLength(int otpLength) {
        this.otpLength = otpLength;
    }

    public int getExpiryMinutes() {
        return expiryMinutes > 0 ? Math.min(expiryMinutes, 5) : 5;
    }

    public void setExpiryMinutes(int expiryMinutes) {
        this.expiryMinutes = expiryMinutes;
    }

    public String getSubject() {
        return subject == null || subject.isBlank() ? "Your verification code" : subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getFrom() {
        return from == null || from.isBlank() ? "noreply@localhost" : from;
    }

    public void setFrom(String from) {
        this.from = from;
    }
}
