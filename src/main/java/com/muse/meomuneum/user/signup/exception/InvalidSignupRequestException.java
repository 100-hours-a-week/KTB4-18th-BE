package com.muse.meomuneum.user.signup.exception;

public class InvalidSignupRequestException extends RuntimeException {
    public InvalidSignupRequestException() {
        super("invalid request");
    }
}
