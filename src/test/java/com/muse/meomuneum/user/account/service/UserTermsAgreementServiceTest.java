package com.muse.meomuneum.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.muse.meomuneum.user.account.exception.UserAccountException;
import com.muse.meomuneum.user.account.repository.TermsAgreementRepository;
import com.muse.meomuneum.user.repository.UserRepository;
import com.muse.meomuneum.user.signup.repository.TermsRepository;

@ExtendWith(MockitoExtension.class)
class UserTermsAgreementServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

    @Mock
    private TermsAgreementRepository termsAgreementRepository;

    @Mock
    private TermsRepository termsRepository;

    @Mock
    private UserRepository userRepository;

    @Test
    void agreesToCurrentTermsAndReturnsCreatedAgreementIdsInRequestOrder() {
        UserTermsAgreementService service = service();
        when(userRepository.findActiveByIdForUpdate(7L))
                .thenReturn(Optional.of(mock(com.muse.meomuneum.user.domain.User.class)));
        when(termsRepository.findCurrent(NOW, null)).thenReturn(List.of(term(11, false), term(12, true)));
        when(termsAgreementRepository.findActiveTermsIds(7L, List.of(12L, 11L))).thenReturn(Set.of());
        when(termsAgreementRepository.create(7L, 12L, NOW)).thenReturn(101L);
        when(termsAgreementRepository.create(7L, 11L, NOW)).thenReturn(102L);

        assertThat(service.agree(7L, List.of(12L, 11L))).containsExactly(101L, 102L);
        verify(userRepository).findActiveByIdForUpdate(7L);
        verify(termsAgreementRepository).create(7L, 12L, NOW);
        verify(termsAgreementRepository).create(7L, 11L, NOW);
    }

    @Test
    void rejectsNonCurrentTermsBeforeWritingAnyAgreement() {
        UserTermsAgreementService service = service();
        when(userRepository.findActiveByIdForUpdate(7L))
                .thenReturn(Optional.of(mock(com.muse.meomuneum.user.domain.User.class)));
        when(termsRepository.findCurrent(NOW, null)).thenReturn(List.of(term(11, false)));

        assertThatThrownBy(() -> service.agree(7L, List.of(11L, 99L)))
                .isInstanceOfSatisfying(UserAccountException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("invalid terms");
                });
        verify(termsAgreementRepository, never()).create(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsNullAndRepeatedIdsBeforeWritingAnyAgreement() {
        UserTermsAgreementService service = service();
        when(userRepository.findActiveByIdForUpdate(7L))
                .thenReturn(Optional.of(mock(com.muse.meomuneum.user.domain.User.class)));

        assertThatThrownBy(() -> service.agree(7L, Arrays.asList(11L, null)))
                .isInstanceOf(UserAccountException.class);
        assertThatThrownBy(() -> service.agree(7L, List.of(11L, 11L)))
                .isInstanceOf(UserAccountException.class);
        verify(termsRepository, never()).findCurrent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull());
        verify(termsAgreementRepository, never()).create(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAlreadyActiveAgreementWithConflict() {
        UserTermsAgreementService service = service();
        when(userRepository.findActiveByIdForUpdate(7L))
                .thenReturn(Optional.of(mock(com.muse.meomuneum.user.domain.User.class)));
        when(termsRepository.findCurrent(NOW, null)).thenReturn(List.of(term(11, false)));
        when(termsAgreementRepository.findActiveTermsIds(7L, List.of(11L))).thenReturn(Set.of(11L));

        assertThatThrownBy(() -> service.agree(7L, List.of(11L)))
                .isInstanceOfSatisfying(UserAccountException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo("already agreed");
                });
        verify(termsAgreementRepository, never()).create(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requiredTermsCannotBeWithdrawnAndOptionalTermsAreSoftDeleted() {
        UserTermsAgreementService service = service();
        when(userRepository.findActiveByIdForUpdate(7L))
                .thenReturn(Optional.of(mock(com.muse.meomuneum.user.domain.User.class)));
        when(termsAgreementRepository.findActiveTermsIds(7L, List.of(12L))).thenReturn(Set.of(12L));
        when(termsRepository.findById(12L)).thenReturn(Optional.of(term(12, true)));
        when(termsAgreementRepository.findActiveTermsIds(7L, List.of(11L))).thenReturn(Set.of(11L));
        when(termsRepository.findById(11L)).thenReturn(Optional.of(term(11, false)));
        when(termsAgreementRepository.withdrawActive(7L, 11L, NOW)).thenReturn(1);

        assertThatThrownBy(() -> service.withdraw(7L, 12L))
                .isInstanceOfSatisfying(UserAccountException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        service.withdraw(7L, 11L);
        verify(termsAgreementRepository).withdrawActive(7L, 11L, NOW);
    }

    @Test
    void missingUserAgreementCannotBeWithdrawn() {
        UserTermsAgreementService service = service();
        when(userRepository.findActiveByIdForUpdate(7L))
                .thenReturn(Optional.of(mock(com.muse.meomuneum.user.domain.User.class)));
        when(termsAgreementRepository.findActiveTermsIds(7L, List.of(11L))).thenReturn(Set.of());

        assertThatThrownBy(() -> service.withdraw(7L, 11L))
                .isInstanceOfSatisfying(UserAccountException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(termsRepository, never()).findById(11L);
    }

    private UserTermsAgreementService service() {
        return new UserTermsAgreementService(termsAgreementRepository, termsRepository, userRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TermsRepository.TermRow term(long id, boolean required) {
        return new TermsRepository.TermRow(id, "SERVICE", "v1", "title", required,
                "2026-09-20T00:00:00Z", "content");
    }
}
