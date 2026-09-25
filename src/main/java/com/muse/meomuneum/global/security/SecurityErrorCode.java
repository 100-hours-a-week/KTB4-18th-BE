package com.muse.meomuneum.global.security;

import org.springframework.http.HttpStatus;

import com.muse.meomuneum.global.exception.ErrorCode;

public enum SecurityErrorCode implements ErrorCode {

    ACCESS_UNAUTHORIZED("SECURITY_401", HttpStatus.UNAUTHORIZED, "unauthorized"), ACCESS_DENIED("SECURITY_403",
            HttpStatus.FORBIDDEN, "request rejected");

    private final String code;
    private final String message;
    private final HttpStatus status;

    SecurityErrorCode(String code, HttpStatus status, String message) {
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
