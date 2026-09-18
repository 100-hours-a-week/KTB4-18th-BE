package com.muse.meomuneum.auth.exception;

import org.springframework.http.HttpStatus;

import com.muse.meomuneum.global.exception.ErrorCode;

public enum AuthErrorCode implements ErrorCode {

    // login 관련 에러코드
    LOGIN_INVALID_REQUEST(
            "AUTH_LOGIN_400", HttpStatus.BAD_REQUEST, "invalid request"
    ),
    LOGIN_INVALID_CREDENTIALS(
            "AUTH_LOGIN_401", HttpStatus.UNAUTHORIZED, "invalid credentials"
    ),
    LOGIN_INTERNAL_SERVER_ERROR(
            "AUTH_LOGIN_500", HttpStatus.INTERNAL_SERVER_ERROR, "internal server error"
    ),

    // refresh 관련 에러 코드
    REFRESH_INVALID_TOKEN(
            "AUTH_REFRESH_401", HttpStatus.UNAUTHORIZED, "invalid refresh token"
    ),
    REFRESH_INTERNAL_SERVER_ERROR(
            "AUTH_REFRESH_500", HttpStatus.INTERNAL_SERVER_ERROR, "internal server error"
    ),

    // csrf 관련 에러코드
    CSRF_REQUEST_FAILED(
            "AUTH_CSRF_500", HttpStatus.INTERNAL_SERVER_ERROR, "request failed"
    );

    private final String code;
    private final String message;
    private final HttpStatus status;

    AuthErrorCode(String code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
