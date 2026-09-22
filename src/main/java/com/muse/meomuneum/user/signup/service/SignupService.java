package com.muse.meomuneum.user.signup.service;

import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.user.signup.dto.SignupRequest;
import com.muse.meomuneum.user.signup.exception.DuplicateEmailException;
import com.muse.meomuneum.user.signup.exception.InvalidSignupRequestException;
import com.muse.meomuneum.user.signup.repository.SignupRepository;

@Service
public class SignupService {
    private static final short MIN_BIRTH_YEAR = 1900;
    private static final String SERVICE = "SERVICE";
    private static final Set<String> SIGNUP_TERMS_TYPES =
            Set.of(SERVICE, "PROFILE", "AIPERSONAL", "LOCATIONTERMS", "LOCATION");

    private final SignupRepository signupRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Autowired
    public SignupService(SignupRepository signupRepository, PasswordEncoder passwordEncoder) {
        this(signupRepository, passwordEncoder, Clock.systemUTC());
    }

    SignupService(SignupRepository signupRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.signupRepository = signupRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public long signup(SignupRequest request) {
        validateBirthYear(request.birthYear());

        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (signupRepository.existsUserByEmail(email)) {
            throw new DuplicateEmailException();
        }

        Set<Long> requestedTermsIds = new LinkedHashSet<>(request.termsIds());
        if (requestedTermsIds.size() != request.termsIds().size()) {
            throw new InvalidSignupRequestException();
        }

        Instant now = clock.instant();
        Map<Long, SignupRepository.Term> termsById = signupRepository.findTermsByIds(requestedTermsIds, now).stream()
                .collect(Collectors.toMap(SignupRepository.Term::id, Function.identity()));
        if (termsById.size() != requestedTermsIds.size() || termsById.values().stream()
                .anyMatch(term -> !SIGNUP_TERMS_TYPES.contains(term.type()))) {
            throw new InvalidSignupRequestException();
        }
        if (termsById.values().stream().noneMatch(term -> SERVICE.equals(term.type()))) {
            throw new InvalidSignupRequestException();
        }

        long userId = signupRepository.createUser(
                email,
                passwordEncoder.encode(request.password()),
                request.nickname().trim(),
                request.birthYear(),
                request.gender(),
                now);
        requestedTermsIds.forEach(termsId -> signupRepository.createTermsAgreement(userId, termsId, now));
        return userId;
    }

    private void validateBirthYear(Short birthYear) {
        if (birthYear == null) {
            return;
        }

        int currentYear = Year.now(clock).getValue();
        if (birthYear < MIN_BIRTH_YEAR || birthYear > currentYear) {
            throw new InvalidSignupRequestException();
        }
    }
}
