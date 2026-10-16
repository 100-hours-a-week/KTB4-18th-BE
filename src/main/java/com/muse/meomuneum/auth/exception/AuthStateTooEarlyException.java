package com.muse.meomuneum.auth.exception;

public class AuthStateTooEarlyException extends RuntimeException {

    private final String code;

    public AuthStateTooEarlyException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
