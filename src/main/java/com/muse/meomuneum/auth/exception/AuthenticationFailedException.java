package com.muse.meomuneum.auth.exception;

import com.muse.meomuneum.global.exception.ErrorCode;

public class AuthenticationFailedException extends RuntimeException {

    private final ErrorCode errorCode;

    public AuthenticationFailedException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
