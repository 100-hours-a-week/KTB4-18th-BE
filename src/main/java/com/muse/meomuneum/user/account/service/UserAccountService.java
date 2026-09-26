package com.muse.meomuneum.user.account.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.user.account.exception.UserAccountException;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserGender;
import com.muse.meomuneum.user.repository.UserRepository;

@Service
public class UserAccountService {

    private static final String INVALID_CREDENTIALS = "AUTH_401";
    private static final String PASSWORD_RECENT = "USER_PASSWORD_RECENT";

    private final Clock clock;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;

    @Autowired
    public UserAccountService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this(userRepository, passwordEncoder, Clock.systemUTC());
    }

    UserAccountService(UserRepository userRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public User getProfile(Long userId) {
        return findActiveUser(userId);
    }

    @Transactional
    public User updateProfile(Long userId, String nickname, Short birthYear, UserGender gender,
            String profileImageUrl) {
        User user = findActiveUserForUpdate(userId);
        user.updateProfile(nickname, birthYear, gender, profileImageUrl, now());
        return user;
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = findActiveUserForUpdate(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new UserAccountException(PASSWORD_RECENT, HttpStatus.BAD_REQUEST, "최근 1년 내 변경된 비밀번호입니다.");
        }
        user.updatePassword(passwordEncoder.encode(newPassword), now());
    }

    @Transactional
    public void withdraw(Long userId, String password) {
        User user = findActiveUserForUpdate(userId);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        user.withdraw(now());
    }

    private User findActiveUser(Long userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId).orElseThrow(this::invalidCredentials);
    }

    private User findActiveUserForUpdate(Long userId) {
        return userRepository.findActiveByIdForUpdate(userId).orElseThrow(this::invalidCredentials);
    }

    private UserAccountException invalidCredentials() {
        return new UserAccountException(INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "invalid credentials");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
