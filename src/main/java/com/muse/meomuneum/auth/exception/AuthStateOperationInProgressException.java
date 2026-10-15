package com.muse.meomuneum.auth.exception;

public class AuthStateOperationInProgressException extends AuthStateConflictException {

    private final String queueCookie;

    public AuthStateOperationInProgressException(String queueCookie) {
        super("OPERATION_IN_PROGRESS");
        this.queueCookie = queueCookie;
    }

    public String queueCookie() {
        return queueCookie;
    }
}
