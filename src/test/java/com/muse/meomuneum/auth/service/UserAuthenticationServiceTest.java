package com.muse.meomuneum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;
import com.muse.meomuneum.user.service.UserAuthenticationService;

class UserAuthenticationServiceTest {

    @Test
    void authenticatesActiveUserWithNormalizedEmailAndMatchingPassword() {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        User user = mock(User.class);
        when(user.getPasswordHash()).thenReturn("hashed-password");
        when(userRepository.findAllByEmailAndDeletedAtIsNull("member@example.com")).thenReturn(List.of(user));
        when(passwordEncoder.matches("password", "hashed-password")).thenReturn(true);
        UserAuthenticationService service = new UserAuthenticationService(passwordEncoder, userRepository);

        assertThat(service.authenticate(" MEMBER@example.com ", "password")).isSameAs(user);
    }

    @Test
    void comparesDummyBcryptHashWhenActiveUserDoesNotExist() {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder());
        UserAuthenticationService service = new UserAuthenticationService(passwordEncoder, userRepository);
        when(userRepository.findAllByEmailAndDeletedAtIsNull("not-found@example.com")).thenReturn(List.of());

        assertInvalidCredentials(() -> service.authenticate("not-found@example.com", "password"));

        verify(passwordEncoder).matches(eq("password"), anyString());
    }

    private void assertInvalidCredentials(Runnable invocation) {
        assertThatThrownBy(invocation::run).isInstanceOf(AuthenticationFailedException.class)
                .extracting(exception -> ((AuthenticationFailedException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }
}
