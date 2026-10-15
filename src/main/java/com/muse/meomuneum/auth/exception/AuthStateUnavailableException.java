package com.muse.meomuneum.auth.exception;

public class AuthStateUnavailableException extends RuntimeException {

    public AuthStateUnavailableException() {
        super("AUTH_STATE_UNAVAILABLE");
    }

    public AuthStateUnavailableException(Throwable cause) {
        super("AUTH_STATE_UNAVAILABLE", cause);
    }
}
