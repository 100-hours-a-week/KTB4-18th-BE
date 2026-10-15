package com.muse.meomuneum.auth.exception;

public class AuthStateTooManyRequestsException extends RuntimeException {

    private final String code;

    public AuthStateTooManyRequestsException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
