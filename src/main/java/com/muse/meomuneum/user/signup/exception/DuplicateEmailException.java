package com.muse.meomuneum.user.signup.exception;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("email already exists");
    }
}
