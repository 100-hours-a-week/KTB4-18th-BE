package com.muse.meomuneum.user.service;

import java.util.List;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;

@Service
public class UserAuthenticationService {

    private static final String DUMMY_PASSWORD_HASH = "$2y$10$SBVBLUI02S31J/CFRlWncuSz.VlzZged/4rQfB9KXFeF.6daJ/yze";

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    public UserAuthenticationService(PasswordEncoder passwordEncoder, UserRepository userRepository) {
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
    }

    public User authenticate(String email, String password) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        List<User> users = userRepository.findAllByEmailAndDeletedAtIsNull(normalizedEmail);

        String passwordHash = users.size() == 1 ? users.getFirst().getPasswordHash() : DUMMY_PASSWORD_HASH;
        boolean passwordMatches = passwordEncoder.matches(password, passwordHash);

        if (users.size() != 1 || !passwordMatches) {
            throw invalidCredentials();
        }

        return users.getFirst();
    }

    public User findActiveUser(Long userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN));
    }

    private AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException(AuthErrorCode.LOGIN_INVALID_CREDENTIALS);
    }
}
