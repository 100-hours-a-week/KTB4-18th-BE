package com.muse.meomuneum.global.exception;

import org.springframework.http.HttpStatus;

public enum GlobalErrorCode implements ErrorCode {

    INTERNAL_SERVER_ERROR(
            "GLOBAL_500", HttpStatus.INTERNAL_SERVER_ERROR, "internal server error"
    );

    private final String code;
    private final String message;
    private final HttpStatus status;

    GlobalErrorCode(String code, HttpStatus status, String message) {
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
