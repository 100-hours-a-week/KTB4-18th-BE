package com.muse.meomuneum.user.signup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.muse.meomuneum.user.signup.dto.SignupRequest;
import com.muse.meomuneum.user.signup.exception.DuplicateEmailException;
import com.muse.meomuneum.user.signup.exception.InvalidSignupRequestException;
import com.muse.meomuneum.user.signup.repository.SignupRepository;

class SignupServiceTest {
    @Test
    void includesLocationAgreementWhenSelectedAtSignup() {
        SignupRepository repository = mock(SignupRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        Instant now = Instant.parse("2026-09-20T00:00:00Z");
        SignupService service = new SignupService(repository, passwordEncoder, Clock.fixed(now, ZoneOffset.UTC));
        SignupRequest request = new SignupRequest("member@example.com", "password1", "머문음", null, null,
                List.of(1L, 4L));

        when(repository.findCurrentSignupTerms(eq(now))).thenReturn(List
                .of(new SignupRepository.Term(1L, "SERVICE", true), new SignupRepository.Term(4L, "LOCATION", false)));
        when(passwordEncoder.encode("password1")).thenReturn("encoded-password1");
        when(repository.createUser(any(), any(), any(), any(), any(), eq(now))).thenReturn(7L);

        assertEquals(7L, service.signup(request));

        verify(repository).createTermsAgreement(7L, 1L, now);
        verify(repository).createTermsAgreement(7L, 4L, now);
    }

    @Test
    void rejectsPrivacyNoticeAsASignupAgreement() {
        SignupRepository repository = mock(SignupRepository.class);
        SignupService service = new SignupService(repository, mock(PasswordEncoder.class), Clock.systemUTC());
        SignupRequest request = new SignupRequest("member@example.com", "password1", "머문음", null, null,
                List.of(1L, 5L));

        when(repository.findCurrentSignupTerms(any())).thenReturn(List
                .of(new SignupRepository.Term(1L, "SERVICE", true), new SignupRepository.Term(5L, "PRIVACY", false)));

        assertThrows(InvalidSignupRequestException.class, () -> service.signup(request));
    }

    @Test
    void rejectsBirthYearBefore1900() {
        SignupRepository repository = mock(SignupRepository.class);
        SignupService service = new SignupService(repository, mock(PasswordEncoder.class), fixedClock());
        SignupRequest request = new SignupRequest("member@example.com", "password1", "머문음", (short) 1899, null,
                List.of(1L));

        assertThrows(InvalidSignupRequestException.class, () -> service.signup(request));
    }

    @Test
    void rejectsBirthYearAfterCurrentYear() {
        SignupRepository repository = mock(SignupRepository.class);
        SignupService service = new SignupService(repository, mock(PasswordEncoder.class), fixedClock());
        SignupRequest request = new SignupRequest("member@example.com", "password1", "머문음", (short) 2027, null,
                List.of(1L));

        assertThrows(InvalidSignupRequestException.class, () -> service.signup(request));
    }

    @Test
    void rejectsPreviousTermsVersionWhenCurrentVersionExists() {
        SignupRepository repository = mock(SignupRepository.class);
        SignupService service = new SignupService(repository, mock(PasswordEncoder.class), fixedClock());
        SignupRequest request = new SignupRequest("member@example.com", "password1", "머문음", null, null, List.of(1L));

        when(repository.findCurrentSignupTerms(any()))
                .thenReturn(List.of(new SignupRepository.Term(2L, "SERVICE", true)));

        assertThrows(InvalidSignupRequestException.class, () -> service.signup(request));
        verify(repository, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void requiresEveryCurrentRequiredTermsId() {
        SignupRepository repository = mock(SignupRepository.class);
        SignupService service = new SignupService(repository, mock(PasswordEncoder.class), fixedClock());
        SignupRequest request = new SignupRequest("member@example.com", "password1", "머문음", null, null, List.of(1L));

        when(repository.findCurrentSignupTerms(any())).thenReturn(List
                .of(new SignupRepository.Term(1L, "SERVICE", true), new SignupRepository.Term(3L, "PROFILE", true)));

        assertThrows(InvalidSignupRequestException.class, () -> service.signup(request));
        verify(repository, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void convertsDuplicateKeyFromUserInsertToDuplicateEmail() {
        SignupRepository repository = mock(SignupRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        SignupService service = new SignupService(repository, passwordEncoder, fixedClock());
        SignupRequest request = new SignupRequest("member@example.com", "password1", "머문음", null, null, List.of(1L));

        when(repository.findCurrentSignupTerms(any()))
                .thenReturn(List.of(new SignupRepository.Term(1L, "SERVICE", true)));
        when(passwordEncoder.encode("password1")).thenReturn("encoded-password1");
        when(repository.createUser(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate email"));

        assertThrows(DuplicateEmailException.class, () -> service.signup(request));
        verify(repository, never()).createTermsAgreement(anyLong(), anyLong(), any());
    }

    @Test
    void rejectsAmbiguousCurrentTerms() {
        SignupRepository repository = mock(SignupRepository.class);
        SignupService service = new SignupService(repository, mock(PasswordEncoder.class), fixedClock());
        SignupRequest request = new SignupRequest("member@example.com", "password1", "머문음", null, null,
                List.of(1L, 2L));

        when(repository.findCurrentSignupTerms(any())).thenReturn(List
                .of(new SignupRepository.Term(1L, "SERVICE", true), new SignupRepository.Term(2L, "SERVICE", true)));

        assertThrows(IllegalStateException.class, () -> service.signup(request));
        verify(repository, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
    }
}
