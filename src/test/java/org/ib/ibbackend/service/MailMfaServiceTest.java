package org.ib.ibbackend.service;

import org.ib.ibbackend.config.MailMfaProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailMfaServiceTest {

    @Test
    void defaultPropertiesAreSafe() {
        MailMfaProperties properties = new MailMfaProperties();

        assertFalse(properties.isEnabled());
        assertTrue(properties.getOtpLength() > 0);
        assertTrue(properties.getExpiryMinutes() > 0);
    }

    @Test
    void otpGenerationAndVerificationWorks() {
        MailMfaProperties properties = new MailMfaProperties();
        properties.setEnabled(true);
        properties.setOtpLength(6);
        properties.setExpiryMinutes(5);

        MailMfaService service = new MailMfaService(properties, null);
        String otp = service.issueOtp("test@example.com");

        assertNotNull(otp);
        assertEquals(6, otp.length());
        assertTrue(service.verifyOtp("test@example.com", otp));
        assertFalse(service.verifyOtp("test@example.com", "000000"));
    }
}
