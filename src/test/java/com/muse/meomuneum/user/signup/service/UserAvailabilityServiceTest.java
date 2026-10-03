package com.muse.meomuneum.user.signup.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.muse.meomuneum.user.signup.exception.InvalidAvailabilityRequestException;
import com.muse.meomuneum.user.signup.repository.SignupRepository;

class UserAvailabilityServiceTest {
    private SignupRepository signupRepository;
    private UserAvailabilityService service;

    @BeforeEach
    void setUp() {
        signupRepository = mock(SignupRepository.class);
        service = new UserAvailabilityService(signupRepository);
    }

    @Test
    void trimsNicknameBeforeCheckingIt() {
        when(signupRepository.existsUserByNickname("한글Ab12")).thenReturn(true);

        assertFalse(service.isAvailable("nickname", "  한글Ab12  "));

        verify(signupRepository).existsUserByNickname("한글Ab12");
    }

    @Test
    void trimsAndLowercasesEmailBeforeCheckingIt() {
        when(signupRepository.existsUserByEmail("qa@example.com")).thenReturn(false);

        assertTrue(service.isAvailable("email", " QA@EXAMPLE.COM "));

        verify(signupRepository).existsUserByEmail("qa@example.com");
    }

    @Test
    void rejectsUnsupportedOrInvalidFieldsWithoutQueryingUsers() {
        assertThrows(InvalidAvailabilityRequestException.class, () -> service.isAvailable("password", "x"));
        assertThrows(InvalidAvailabilityRequestException.class, () -> service.isAvailable("nickname", "한 글"));
        assertThrows(InvalidAvailabilityRequestException.class, () -> service.isAvailable("email", "invalid"));
        assertThrows(InvalidAvailabilityRequestException.class, () -> service.isAvailable("email", null));

        verifyNoInteractions(signupRepository);
    }
}
