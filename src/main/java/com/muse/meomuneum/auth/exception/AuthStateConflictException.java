package com.muse.meomuneum.auth.exception;

public class AuthStateConflictException extends RuntimeException {

    private final String code;

    public AuthStateConflictException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
