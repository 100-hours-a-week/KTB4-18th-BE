package com.muse.meomuneum.user.service;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.repository.UserRepository;

@Service
public class UserAuthenticationService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    public UserAuthenticationService(PasswordEncoder passwordEncoder, UserRepository userRepository) {
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
    }

    public User authenticate(String email, String password) {
        List<User> users = userRepository.findAllByEmailAndDeletedAtIsNull(email);

        if (users.size() != 1 || !passwordEncoder.matches(password, users.getFirst().getPasswordHash())) {
            throw new AuthenticationFailedException(AuthErrorCode.LOGIN_INVALID_CREDENTIALS);
        }

        return users.getFirst();
    }

    public User findActiveUser(Long userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN));
    }
}
