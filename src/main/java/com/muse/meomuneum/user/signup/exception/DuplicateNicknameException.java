package com.muse.meomuneum.user.signup.exception;

public class DuplicateNicknameException extends RuntimeException {
    public DuplicateNicknameException() {
        super("nickname already exists");
    }
}
