package com.muse.meomuneum.user.signup.service;

import java.util.regex.Pattern;

import jakarta.validation.Validator;

import org.springframework.stereotype.Service;

import com.muse.meomuneum.user.signup.dto.SignupRequest;
import com.muse.meomuneum.user.signup.exception.InvalidAvailabilityRequestException;
import com.muse.meomuneum.user.signup.repository.SignupRepository;

@Service
public class UserAvailabilityService {
    private static final Pattern NICKNAME_PATTERN = Pattern.compile("^[가-힣A-Za-z0-9]{2,12}$");

    private final SignupRepository signupRepository;
    private final Validator validator;

    public UserAvailabilityService(SignupRepository signupRepository, Validator validator) {
        this.signupRepository = signupRepository;
        this.validator = validator;
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
            String email = SignupRequest.normalizeEmail(value);
            if (!validator.validateValue(SignupRequest.class, "email", email).isEmpty()) {
                throw new InvalidAvailabilityRequestException();
            }
            return !signupRepository.existsUserByEmail(email);
        }

        throw new InvalidAvailabilityRequestException();
    }
}
