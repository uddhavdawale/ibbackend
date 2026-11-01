package org.ib.ibbackend.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordSecurityServiceTest {

    private final PasswordSecurityService passwordSecurityService =
            new PasswordSecurityService(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());

    @Test
    void hashNewPassword_shouldGenerateDifferentHashesForSamePassword() {
        String rawPassword = "myStrongPassword123";

        String firstHash = passwordSecurityService.hashNewPassword(rawPassword);
        String secondHash = passwordSecurityService.hashNewPassword(rawPassword);

        assertTrue(firstHash.startsWith("$argon2id$"));
        assertTrue(secondHash.startsWith("$argon2id$"));
        assertNotEquals(firstHash, secondHash);
        assertTrue(passwordSecurityService.matches(rawPassword, firstHash));
        assertTrue(passwordSecurityService.matches(rawPassword, secondHash));
    }

    @Test
    void hashNewPassword_shouldRejectPasswordShorterThan12Characters() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> passwordSecurityService.hashNewPassword("short123")
        );

        assertTrue(ex.getMessage().contains("at least 12"));
    }
}
