package com.muse.meomuneum.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthErrorCodeResolver;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;

class ErrorCodeTest {

    @Test
    void authenticationFailureKeepsTheProvidedApiErrorCode() {
        AuthenticationFailedException exception = new AuthenticationFailedException(
                AuthErrorCode.LOGIN_INVALID_CREDENTIALS);

        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_INVALID_CREDENTIALS);
        assertThat(exception.getErrorCode().code()).isEqualTo("AUTH_LOGIN_401");
        assertThat(exception.getMessage()).isEqualTo(AuthErrorCode.LOGIN_INVALID_CREDENTIALS.message());
        assertThat(exception.getErrorCode().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authApiErrorCodesMatchTheSpecifiedStatusesAndMessages() {
        assertThat(AuthErrorCode.LOGIN_INVALID_REQUEST.code()).isEqualTo("AUTH_LOGIN_400");
        assertThat(AuthErrorCode.LOGIN_INVALID_CREDENTIALS.message()).isEqualTo("invalid credentials");
        assertThat(AuthErrorCode.LOGIN_INTERNAL_SERVER_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(AuthErrorCode.REFRESH_INVALID_TOKEN.code()).isEqualTo("AUTH_REFRESH_401");
        assertThat(AuthErrorCode.REFRESH_INVALID_TOKEN.message()).isEqualTo("invalid refresh token");
        assertThat(AuthErrorCode.REFRESH_INTERNAL_SERVER_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(AuthErrorCode.CSRF_REQUEST_FAILED.code()).isEqualTo("AUTH_CSRF_500");
        assertThat(AuthErrorCode.CSRF_REQUEST_FAILED.message()).isEqualTo("request failed");
    }

    @Test
    void internalServerErrorIsSelectedForTheRelevantAuthenticationApi() {
        HttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/token/csrf");

        ErrorCode errorCode = AuthErrorCodeResolver.resolveInternalServerError(request);

        assertThat(errorCode).isEqualTo(AuthErrorCode.CSRF_REQUEST_FAILED);
    }
}
