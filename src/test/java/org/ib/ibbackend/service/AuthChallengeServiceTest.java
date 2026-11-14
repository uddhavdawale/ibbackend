package org.ib.ibbackend.service;

import org.ib.ibbackend.config.AuthFlowProperties;
import org.ib.ibbackend.config.MailMfaProperties;
import org.ib.ibbackend.entity.AuthChallenge;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.repository.AuthChallengeRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthChallengeServiceTest {
    @Test
    void otpIsStoredAsKeyedDigestAndConsumedOnce() {
        AuthChallengeRepository repository = mock(AuthChallengeRepository.class);
        AtomicReference<AuthChallenge> stored = new AtomicReference<>();
        when(repository.save(any(AuthChallenge.class))).thenAnswer(invocation -> {
            AuthChallenge value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        when(repository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        AuthFlowProperties properties = new AuthFlowProperties();
        properties.setMfaOtpHmacSecret("0123456789abcdef0123456789abcdef");
        AuthChallengeService service = new AuthChallengeService(repository, properties, new MailMfaProperties());
        User user = new User();
        user.setId(7L);
        user.setEmail("owner@example.com");

        AuthChallengeService.IssuedChallenge issued = service.createChallenge(user);
        AuthChallengeService.PendingOtp pending = service.prepareOtp(issued.challengeId(), issued.cookieValue());
        AuthChallenge challenge = stored.get();

        assertThat(pending.otp()).matches("\\d{6}");
        assertThat(challenge.getOtpDigest()).hasSize(64).isNotEqualTo(pending.otp());
        assertThat(service.verifyOtp(issued.challengeId(), issued.cookieValue(), pending.otp()))
                .contains(user);
        assertThat(challenge.getConsumedAt()).isNotNull();
        assertThat(service.verifyOtp(issued.challengeId(), issued.cookieValue(), pending.otp())).isEmpty();
    }

    @Test
    void challengeRequiresItsPreAuthenticationCookie() {
        AuthChallengeRepository repository = mock(AuthChallengeRepository.class);
        AtomicReference<AuthChallenge> stored = new AtomicReference<>();
        when(repository.save(any(AuthChallenge.class))).thenAnswer(invocation -> {
            AuthChallenge value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        when(repository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        AuthFlowProperties properties = new AuthFlowProperties();
        properties.setMfaOtpHmacSecret("0123456789abcdef0123456789abcdef");
        AuthChallengeService service = new AuthChallengeService(repository, properties, new MailMfaProperties());
        User user = new User();
        user.setId(3L);
        user.setEmail("owner@example.com");
        AuthChallengeService.IssuedChallenge issued = service.createChallenge(user);

        assertThatThrownBy(() -> service.prepareOtp(issued.challengeId(), "wrong-cookie"))
                .isInstanceOf(AuthChallengeService.ChallengeLimitException.class);
    }

    @Test
    void requestsMustProvideTheChallengeId() {
        AuthChallengeRepository repository = mock(AuthChallengeRepository.class);
        AuthFlowProperties properties = new AuthFlowProperties();
        properties.setMfaOtpHmacSecret("0123456789abcdef0123456789abcdef");
        AuthChallengeService service = new AuthChallengeService(repository, properties, new MailMfaProperties());
        User user = new User();
        user.setId(5L);
        user.setEmail("owner@example.com");
        AuthChallengeService.IssuedChallenge issued = service.createChallenge(user);

        assertThatThrownBy(() -> service.prepareOtp(null, issued.cookieValue()))
                .isInstanceOf(AuthChallengeService.ChallengeLimitException.class);
    }

    @Test
    void cancellingChallengeInvalidatesItsOtpAndPreAuthenticationCookie() {
        AuthChallengeRepository repository = mock(AuthChallengeRepository.class);
        AtomicReference<AuthChallenge> stored = new AtomicReference<>();
        when(repository.save(any(AuthChallenge.class))).thenAnswer(invocation -> {
            AuthChallenge value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        when(repository.findByIdForUpdate(any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        AuthFlowProperties properties = new AuthFlowProperties();
        properties.setMfaOtpHmacSecret("0123456789abcdef0123456789abcdef");
        AuthChallengeService service = new AuthChallengeService(repository, properties, new MailMfaProperties());
        User user = new User();
        user.setId(6L);
        user.setEmail("owner@example.com");
        AuthChallengeService.IssuedChallenge issued = service.createChallenge(user);
        service.prepareOtp(issued.challengeId(), issued.cookieValue());

        service.cancelChallenge(issued.challengeId(), issued.cookieValue());

        assertThat(stored.get().getConsumedAt()).isNotNull();
        assertThat(stored.get().getOtpDigest()).isNull();
        assertThat(stored.get().getOtpExpiresAt()).isNull();
        assertThat(service.verifyOtp(issued.challengeId(), issued.cookieValue(), "123456")).isEmpty();
    }
}
