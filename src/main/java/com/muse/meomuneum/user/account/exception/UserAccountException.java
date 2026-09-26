package com.muse.meomuneum.user.account.exception;

import org.springframework.http.HttpStatus;

public class UserAccountException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public UserAccountException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
