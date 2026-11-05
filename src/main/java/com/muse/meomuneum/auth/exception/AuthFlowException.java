package com.muse.meomuneum.auth.exception;

import org.springframework.http.HttpStatus;

public class AuthFlowException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public AuthFlowException(String code) {
        super(code);
        this.code = code;
        this.status = statusFor(code);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    private HttpStatus statusFor(String code) {
        return switch (code) {
            case "AUTH_FLOW_REQUIRED", "AUTH_FLOW_COOKIE_INVALID" -> HttpStatus.PRECONDITION_REQUIRED;
            case "AUTH_SESSION_INVALID" -> HttpStatus.UNAUTHORIZED;
            case "AUTH_FLOW_BOOTSTRAP_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "INVALID_OPERATION_ID", "AUTH_FLOW_REVISION_INVALID" -> HttpStatus.BAD_REQUEST;
            case "AUTH_FLOW_REVISION_REQUIRED" -> HttpStatus.PRECONDITION_REQUIRED;
            default -> HttpStatus.CONFLICT;
        };
    }
}
