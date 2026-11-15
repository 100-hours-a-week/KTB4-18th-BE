package com.muse.meomuneum.auth.exception;

public class AuthStateBusyException extends RuntimeException {

    public AuthStateBusyException() {
        super("AUTH_STATE_BUSY");
    }
}
