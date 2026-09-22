package com.muse.meomuneum.auth.exception;

import org.springframework.http.HttpStatus;

import com.muse.meomuneum.global.exception.ErrorCode;

public enum AuthErrorCode implements ErrorCode {

    INVALID_REQUEST(
            "AUTH_400", HttpStatus.BAD_REQUEST, "invalid request"
    ),
    INVALID_CREDENTIALS(
            "AUTH_401", HttpStatus.UNAUTHORIZED, "invalid credentials"
    ),

    // POST /api/v1/auth/token/refresh
    REFRESH_INVALID_TOKEN(
            "AUTH_REFRESH_401", HttpStatus.UNAUTHORIZED, "invalid refresh token"
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
