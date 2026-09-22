package com.muse.meomuneum.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.response.AuthSuccessCode;
import com.muse.meomuneum.global.security.SecurityErrorCode;

class ErrorCodeTest {

    @Test
    void authenticationFailureKeepsTheProvidedApiErrorCode() {
        AuthenticationFailedException exception = new AuthenticationFailedException(
                AuthErrorCode.INVALID_CREDENTIALS);

        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        assertThat(exception.getErrorCode().code()).isEqualTo("AUTH_401");
        assertThat(exception.getMessage()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS.message());
        assertThat(exception.getErrorCode().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authAndSecurityCodesMatchTheirResponsibility() {
        assertThat(AuthErrorCode.INVALID_REQUEST.code()).isEqualTo("AUTH_400");
        assertThat(AuthErrorCode.INVALID_CREDENTIALS.message()).isEqualTo("invalid credentials");
        assertThat(AuthErrorCode.REFRESH_INVALID_TOKEN.code()).isEqualTo("AUTH_REFRESH_401");
        assertThat(AuthErrorCode.REFRESH_INVALID_TOKEN.message()).isEqualTo("invalid refresh token");
        assertThat(SecurityErrorCode.ACCESS_UNAUTHORIZED.code()).isEqualTo("SECURITY_401");
        assertThat(SecurityErrorCode.ACCESS_DENIED.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void successAndGlobalCodesUseTheSpecifiedStatusesAndMessages() {
        assertThat(AuthSuccessCode.LOGIN_SUCCESS.code()).isEqualTo("AUTH_200");
        assertThat(AuthSuccessCode.LOGIN_SUCCESS.status()).isEqualTo(HttpStatus.OK);
        assertThat(GlobalErrorCode.INTERNAL_SERVER_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
