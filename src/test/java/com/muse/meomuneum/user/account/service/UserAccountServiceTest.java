package com.muse.meomuneum.user.account.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.muse.meomuneum.user.account.exception.UserAccountException;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserRepository userRepository;

    @Test
    void doesNotRevealThatNewPasswordMatchesCurrentPasswordWhenCurrentPasswordIsInvalid() {
        User user = mock(User.class);
        UserAccountService service = new UserAccountService(userRepository, passwordEncoder,
                Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC));
        when(userRepository.findActiveByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(user.getPasswordHash()).thenReturn("password-hash");
        when(passwordEncoder.matches("wrong-current", "password-hash")).thenReturn(false);
        when(passwordEncoder.matches("new-password", "password-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(1L, "wrong-current", "new-password"))
                .isInstanceOf(UserAccountException.class).hasMessage("최근 1년 내 변경된 비밀번호입니다.");
        verify(passwordEncoder, times(2)).matches(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("password-hash"));
    }

    @Test
    void describesTheCurrentPasswordAsRecentlyChangedWhenTheNewPasswordMatchesIt() {
        User user = mock(User.class);
        UserAccountService service = new UserAccountService(userRepository, passwordEncoder,
                Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC));
        when(userRepository.findActiveByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(user.getPasswordHash()).thenReturn("password-hash");
        when(passwordEncoder.matches("current-password", "password-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(1L, "current-password", "current-password"))
                .isInstanceOf(UserAccountException.class)
                .hasMessage("최근 1년 내 변경된 비밀번호입니다.");
    }

    @Test
    void withdrawalWithWrongPasswordRetainsInvalidCredentialsResponse() {
        User user = mock(User.class);
        UserAccountService service = new UserAccountService(userRepository, passwordEncoder,
                Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC));
        when(userRepository.findActiveByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(user.getPasswordHash()).thenReturn("password-hash");
        when(passwordEncoder.matches("wrong-password", "password-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.withdraw(1L, "wrong-password"))
                .isInstanceOf(UserAccountException.class)
                .hasMessage("invalid credentials");
    }

    @Test
    void inactiveUserRetainsInvalidCredentialsResponse() {
        UserAccountService service = new UserAccountService(userRepository, passwordEncoder,
                Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC));
        when(userRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProfile(1L))
                .isInstanceOf(UserAccountException.class)
                .hasMessage("invalid credentials");
    }
}
