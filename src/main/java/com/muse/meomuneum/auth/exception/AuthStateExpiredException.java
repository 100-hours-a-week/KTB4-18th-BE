package com.muse.meomuneum.auth.exception;

public class AuthStateExpiredException extends RuntimeException {

    public AuthStateExpiredException() {
        super("TICKET_EXPIRED");
    }
}
