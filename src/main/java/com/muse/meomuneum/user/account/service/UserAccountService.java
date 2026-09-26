package com.muse.meomuneum.user.account.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final String PASSWORD_RECENT_MESSAGE = "최근 1년 내 변경된 비밀번호입니다.";
    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

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
        boolean currentPasswordMatches = passwordEncoder.matches(currentPassword, user.getPasswordHash());
        boolean newPasswordMatches = passwordEncoder.matches(newPassword, user.getPasswordHash());
        if (!currentPasswordMatches) {
            logPasswordChangeRejection("current_password_mismatch");
            throw passwordRecent();
        }
        if (newPasswordMatches) {
            logPasswordChangeRejection("new_password_matches_current");
            throw passwordRecent();
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

    private UserAccountException passwordRecent() {
        return new UserAccountException(PASSWORD_RECENT, HttpStatus.BAD_REQUEST, PASSWORD_RECENT_MESSAGE);
    }

    private void logPasswordChangeRejection(String reason) {
        log.warn("event=user_password_change_rejected reason={}", reason);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
