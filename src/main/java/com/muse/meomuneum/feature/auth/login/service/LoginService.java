package com.muse.meomuneum.feature.auth.login.service;

import com.muse.meomuneum.feature.auth.login.InvalidCredentialsException;
import com.muse.meomuneum.feature.auth.login.attempt.LoginAttemptStore;
import com.muse.meomuneum.feature.auth.login.dto.LoginRequest;
import com.muse.meomuneum.feature.auth.login.token.IssuedTokens;
import com.muse.meomuneum.feature.auth.login.token.TokenIssuer;
import com.muse.meomuneum.feature.auth.login.user.UserAccount;
import com.muse.meomuneum.feature.auth.login.user.UserRepository;

import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final LoginAttemptStore loginAttemptStore;

    public LoginService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenIssuer tokenIssuer,
            LoginAttemptStore loginAttemptStore) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.loginAttemptStore = loginAttemptStore;
    }

    @Transactional(readOnly = true)
    public IssuedTokens login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        if (loginAttemptStore.isBlocked(email)) {
            throw new InvalidCredentialsException();
        }

        UserAccount user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.isDeleted()) {
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            loginAttemptStore.recordFailure(email);
            throw new InvalidCredentialsException();
        }

        loginAttemptStore.clearAfterSuccessfulLogin(email);
        return tokenIssuer.issue(user);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
