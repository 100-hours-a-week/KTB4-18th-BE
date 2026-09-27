package com.muse.meomuneum.user.account.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muse.meomuneum.user.account.exception.UserAccountException;
import com.muse.meomuneum.user.account.repository.TermsAgreementRepository;
import com.muse.meomuneum.user.repository.UserRepository;
import com.muse.meomuneum.user.signup.repository.TermsRepository;

@Service
public class UserTermsAgreementService {

    private static final String INVALID_TERMS = "TERMS_INVALID";
    private static final String AGREEMENT_NOT_FOUND = "TERMS_AGREEMENT_NOT_FOUND";

    private final Clock clock;
    private final TermsAgreementRepository termsAgreementRepository;
    private final TermsRepository termsRepository;
    private final UserRepository userRepository;

    @Autowired
    public UserTermsAgreementService(TermsAgreementRepository termsAgreementRepository,
            TermsRepository termsRepository, UserRepository userRepository) {
        this(termsAgreementRepository, termsRepository, userRepository, Clock.systemUTC());
    }

    UserTermsAgreementService(TermsAgreementRepository termsAgreementRepository,
            TermsRepository termsRepository, UserRepository userRepository, Clock clock) {
        this.termsAgreementRepository = termsAgreementRepository;
        this.termsRepository = termsRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AgreementItem> getAgreements(Long userId) {
        userRepository.findByIdAndDeletedAtIsNull(userId).orElseThrow(this::unauthorized);
        return termsAgreementRepository.findAllByUserId(userId).stream()
                .map(row -> new AgreementItem(row.agreementId(), row.termsId(), row.agreedAt(), row.withdrawnAt()))
                .toList();
    }

    @Transactional
    public List<Long> agree(Long userId, List<Long> termsIds) {
        userRepository.findActiveByIdForUpdate(userId).orElseThrow(this::unauthorized);
        validateTermsIds(termsIds);

        Instant now = clock.instant();
        Map<Long, TermsRepository.TermRow> currentTermsById = termsRepository.findCurrent(now, null).stream()
                .collect(Collectors.toMap(TermsRepository.TermRow::id, Function.identity()));
        if (!currentTermsById.keySet().containsAll(termsIds)) {
            throw invalidTerms();
        }

        Set<Long> activeTermsIds = termsAgreementRepository.findActiveTermsIds(userId, termsIds);
        if (!activeTermsIds.isEmpty()) {
            throw new UserAccountException("TERMS_ALREADY_AGREED", HttpStatus.CONFLICT, "already agreed");
        }

        List<Long> agreementIds = new ArrayList<>(termsIds.size());
        for (Long termsId : termsIds) {
            agreementIds.add(termsAgreementRepository.create(userId, termsId, now));
        }
        return List.copyOf(agreementIds);
    }

    @Transactional
    public void withdraw(Long userId, long termsId) {
        userRepository.findActiveByIdForUpdate(userId).orElseThrow(this::unauthorized);
        if (termsAgreementRepository.findActiveTermsIds(userId, List.of(termsId)).isEmpty()) {
            throw new UserAccountException(AGREEMENT_NOT_FOUND, HttpStatus.NOT_FOUND, "not found");
        }

        TermsRepository.TermRow term = termsRepository.findById(termsId)
                .orElseThrow(() -> new UserAccountException(AGREEMENT_NOT_FOUND, HttpStatus.NOT_FOUND, "not found"));
        if (term.required()) {
            throw new UserAccountException("TERMS_REQUIRED", HttpStatus.FORBIDDEN, "forbidden");
        }

        int updated = termsAgreementRepository.withdrawActive(userId, termsId, clock.instant());
        if (updated == 0) {
            throw new UserAccountException(AGREEMENT_NOT_FOUND, HttpStatus.NOT_FOUND, "not found");
        }
    }

    private void validateTermsIds(List<Long> termsIds) {
        if (termsIds == null || termsIds.isEmpty() || termsIds.stream().anyMatch(id -> id == null)
                || new LinkedHashSet<>(termsIds).size() != termsIds.size()) {
            throw invalidTerms();
        }
    }

    private UserAccountException invalidTerms() {
        return new UserAccountException(INVALID_TERMS, HttpStatus.BAD_REQUEST, "invalid terms");
    }

    private UserAccountException unauthorized() {
        return new UserAccountException("AUTH_401", HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    public record AgreementItem(long agreement_id, long terms_id, String agreed_at, String withdrawn_at) {
    }
}
