package com.muse.meomuneum.user.signup.service;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.muse.meomuneum.user.signup.exception.InvalidAvailabilityRequestException;
import com.muse.meomuneum.user.signup.repository.SignupRepository;

@Service
public class UserAvailabilityService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern NICKNAME_PATTERN = Pattern.compile("^[가-힣A-Za-z0-9]{2,12}$");
    private static final int MAX_EMAIL_LENGTH = 40;

    private final SignupRepository signupRepository;

    public UserAvailabilityService(SignupRepository signupRepository) {
        this.signupRepository = signupRepository;
    }

    public boolean isAvailable(String field, String value) {
        if (field == null || value == null) {
            throw new InvalidAvailabilityRequestException();
        }

        if ("nickname".equals(field)) {
            String nickname = value.trim();
            if (!NICKNAME_PATTERN.matcher(nickname).matches()) {
                throw new InvalidAvailabilityRequestException();
            }
            return !signupRepository.existsUserByNickname(nickname);
        }

        if ("email".equals(field)) {
            String email = value.trim().toLowerCase(Locale.ROOT);
            if (email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
                throw new InvalidAvailabilityRequestException();
            }
            return !signupRepository.existsUserByEmail(email);
        }

        throw new InvalidAvailabilityRequestException();
    }
}
