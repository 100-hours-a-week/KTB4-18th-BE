package com.muse.meomuneum.feature.auth.login.service;

import com.muse.meomuneum.feature.auth.login.InvalidCredentialsException;
import com.muse.meomuneum.feature.auth.login.attempt.LoginAttemptStore;
import com.muse.meomuneum.feature.auth.login.dto.LoginRequest;
import com.muse.meomuneum.feature.auth.login.token.IssuedTokens;
import com.muse.meomuneum.feature.auth.login.token.TokenIssuer;
import com.muse.meomuneum.feature.auth.login.user.UserAccount;
import com.muse.meomuneum.feature.auth.login.user.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock TokenIssuer tokenIssuer;

    private LoginAttemptStore loginAttemptStore;
    private LoginService loginService;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        loginAttemptStore = new LoginAttemptStore(clock);
        loginService = new LoginService(
                userRepository,
                passwordEncoder,
                tokenIssuer,
                loginAttemptStore);
    }

    @Test
    void issuesTokensForActiveUserWithMatchingPassword() {
        LoginRequest request = new LoginRequest("MEMBER@example.com", "password123");
        UserAccount user = new UserAccount(1L, "member@example.com", "hashed-password", null);
        IssuedTokens expected = new IssuedTokens("access", 3600, "refresh", 1209600);
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed-password")).thenReturn(true);
        when(tokenIssuer.issue(user)).thenReturn(expected);

        IssuedTokens actual = loginService.login(request);

        assertEquals(expected, actual);
    }

    @Test
    void returnsSameCredentialFailureForMissingAndDeletedUsers() {
        LoginRequest request = new LoginRequest("member@example.com", "password123");
        when(userRepository.findByEmail("member@example.com"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new UserAccount(
                        1L,
                        "member@example.com",
                        "hashed-password",
                        LocalDateTime.now())));

        assertThrows(InvalidCredentialsException.class, () -> loginService.login(request));
        assertThrows(InvalidCredentialsException.class, () -> loginService.login(request));
        verify(passwordEncoder, never()).matches("password123", "hashed-password");
    }

    @Test
    void resetsTheFirstLockoutHistoryAfterSuccessfulLogin() {
        LoginRequest wrongPassword = new LoginRequest("member@example.com", "wrong-password");
        LoginRequest correctPassword = new LoginRequest("member@example.com", "password123");
        UserAccount user = new UserAccount(1L, "member@example.com", "hashed-password", null);
        IssuedTokens tokens = new IssuedTokens("access", 3600, "refresh", 1209600);
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);
        when(passwordEncoder.matches("password123", "hashed-password")).thenReturn(true);
        when(tokenIssuer.issue(user)).thenReturn(tokens);

        for (int count = 0; count < 10; count++) {
            assertThrows(InvalidCredentialsException.class, () -> loginService.login(wrongPassword));
        }
        assertTrue(loginAttemptStore.isBlocked("member@example.com"));

        clock.advanceSeconds(301);
        assertEquals(tokens, loginService.login(correctPassword));

        for (int count = 0; count < 10; count++) {
            assertThrows(InvalidCredentialsException.class, () -> loginService.login(wrongPassword));
        }
        assertTrue(loginAttemptStore.isBlocked("member@example.com"));

        clock.advanceSeconds(301);
        assertFalse(loginAttemptStore.isBlocked("member@example.com"));
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
