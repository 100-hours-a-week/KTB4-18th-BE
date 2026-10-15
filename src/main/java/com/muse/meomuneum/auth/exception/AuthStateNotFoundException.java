package com.muse.meomuneum.auth.exception;

public class AuthStateNotFoundException extends RuntimeException {

    private final String code;

    public AuthStateNotFoundException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
