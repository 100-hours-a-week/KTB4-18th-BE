package com.muse.meomuneum.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;
import com.muse.meomuneum.user.service.UserAuthenticationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserAuthenticationServiceTest {

    @Test
    void resetsLockoutHistoryAfterSuccessfulLogin() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        LoginAttemptStore loginAttemptStore = new LoginAttemptStore(clock);
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        User user = mock(User.class);
        when(user.getPasswordHash()).thenReturn("hashed-password");
        when(userRepository.findAllByEmailAndDeletedAtIsNull("member@example.com")).thenReturn(List.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);
        when(passwordEncoder.matches("password", "hashed-password")).thenReturn(true);
        UserAuthenticationService service = new UserAuthenticationService(
                passwordEncoder, userRepository, loginAttemptStore);

        for (int attempt = 0; attempt < 10; attempt++) {
            assertInvalidCredentials(() -> service.authenticate("member@example.com", "wrong-password"));
        }
        assertThat(loginAttemptStore.isBlocked("member@example.com")).isTrue();

        clock.advanceSeconds(301);
        assertThat(service.authenticate(" MEMBER@example.com ", "password")).isSameAs(user);

        for (int attempt = 0; attempt < 10; attempt++) {
            assertInvalidCredentials(() -> service.authenticate("member@example.com", "wrong-password"));
        }
        assertThat(loginAttemptStore.isBlocked("member@example.com")).isTrue();

        clock.advanceSeconds(301);
        assertThat(loginAttemptStore.isBlocked("member@example.com")).isFalse();
    }

    private void assertInvalidCredentials(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(AuthenticationFailedException.class)
                .extracting(exception -> ((AuthenticationFailedException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.LOGIN_INVALID_CREDENTIALS);
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }
    }
}
