package com.muse.meomuneum.user.signup.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.muse.meomuneum.global.response.ApiResponse;

class SignupExceptionHandlerTest {
    private final SignupExceptionHandler exceptionHandler = new SignupExceptionHandler();

    @Test
    void mapsDuplicateEmailToExistingApiResponse() {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.duplicateEmail(new DuplicateEmailException());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("email already exists", response.getBody().message());
        assertNull(response.getBody().data());
    }
}
