package org.ib.ibbackend.service;

import org.ib.ibbackend.config.AuthFlowProperties;
import org.ib.ibbackend.entity.PasswordResetToken;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.PasswordResetTokenRepository;
import org.ib.ibbackend.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {
    @Test
    void resetTokenIsStoredAsDigestAndResetRevokesExistingSessions() {
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        PasswordSecurityService passwordSecurityService = mock(PasswordSecurityService.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        AuthFlowProperties properties = new AuthFlowProperties();
        AtomicReference<PasswordResetToken> stored = new AtomicReference<>();
        when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            stored.set(token);
            return token;
        });
        when(tokenRepository.wasRecentlyIssued(any(), any())).thenReturn(false);
        when(tokenRepository.findByDigestForUpdate(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get()).filter(token ->
                        token.getTokenDigest().equals(invocation.getArgument(0))));
        User user = activeUser();
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findByEmailForUpdate("owner@example.com")).thenReturn(Optional.of(user));
        when(passwordSecurityService.hashNewPassword("Correct Horse Battery 123"))
                .thenReturn("argon2-password-hash");

        PasswordResetService service = new PasswordResetService(
                tokenRepository, userRepository, passwordSecurityService, refreshTokenService, properties);
        PasswordResetService.ResetEmail resetEmail = service.requestReset("OWNER@example.com").orElseThrow();

        assertThat(resetEmail.token()).isNotBlank();
        assertThat(stored.get().getTokenDigest()).hasSize(64).isNotEqualTo(resetEmail.token());
        assertThat(service.resetPassword(resetEmail.token(), "Correct Horse Battery 123"))
                .contains(activeUserReference(stored.get()));
        assertThat(stored.get().getConsumedAt()).isNotNull();
        assertThat(stored.get().getUser().getAuthVersion()).isEqualTo(1);
        assertThat(stored.get().getUser().getPasswordHash()).isEqualTo("argon2-password-hash");
        verify(refreshTokenService).revokeAllActiveTokensForUser(stored.get().getUser());
    }

    private User activeUser() {
        User user = new User();
        user.setId(11L);
        user.setEmail("owner@example.com");
        user.setPasswordHash("old-hash");
        return user;
    }

    private User activeUserReference(PasswordResetToken token) {
        return token.getUser();
    }
}
