package com.muse.meomuneum.user.signup.service;

import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.user.signup.dto.SignupRequest;
import com.muse.meomuneum.user.signup.domain.SignupTermType;
import com.muse.meomuneum.user.signup.exception.DuplicateEmailException;
import com.muse.meomuneum.user.signup.exception.InvalidSignupRequestException;
import com.muse.meomuneum.user.signup.repository.SignupRepository;

@Service
public class SignupService {
    private static final short MIN_BIRTH_YEAR = 1900;
    private static final Set<String> SIGNUP_TERMS_TYPES = SignupTermType.names();

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

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
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
        List<SignupRepository.Term> currentTerms = signupRepository.findCurrentSignupTerms(now);
        validateCurrentTerms(currentTerms);

        Map<Long, SignupRepository.Term> termsById = currentTerms.stream()
                .filter(term -> requestedTermsIds.contains(term.id()))
                .collect(Collectors.toMap(SignupRepository.Term::id, Function.identity()));
        if (termsById.size() != requestedTermsIds.size() || termsById.values().stream()
                .anyMatch(term -> !SIGNUP_TERMS_TYPES.contains(term.type()))) {
            throw new InvalidSignupRequestException();
        }
        Set<Long> requiredTermsIds = currentTerms.stream()
                .filter(SignupRepository.Term::required)
                .map(SignupRepository.Term::id)
                .collect(Collectors.toSet());
        if (requiredTermsIds.isEmpty()) {
            throw new IllegalStateException("Current required terms are missing");
        }
        if (!requestedTermsIds.containsAll(requiredTermsIds)) {
            throw new InvalidSignupRequestException();
        }

        long userId = createUser(request, email, now);
        requestedTermsIds.forEach(termsId -> signupRepository.createTermsAgreement(userId, termsId, now));
        return userId;
    }

    private void validateCurrentTerms(List<SignupRepository.Term> currentTerms) {
        Map<String, Long> currentTermsCountByType = currentTerms.stream()
                .collect(Collectors.groupingBy(SignupRepository.Term::type, Collectors.counting()));
        if (currentTermsCountByType.values().stream().anyMatch(count -> count != 1)) {
            throw new IllegalStateException("Current terms are ambiguous");
        }
        if (!currentTermsCountByType.keySet().equals(SIGNUP_TERMS_TYPES)) {
            throw new IllegalStateException("Current signup terms are incomplete or misconfigured");
        }
    }

    private long createUser(SignupRequest request, String email, Instant now) {
        try {
            return signupRepository.createUser(
                    email,
                    passwordEncoder.encode(request.password()),
                    request.nickname().trim(),
                    request.birthYear(),
                    request.gender(),
                    now);
        } catch (DuplicateKeyException exception) {
            throw new DuplicateEmailException();
        }
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
