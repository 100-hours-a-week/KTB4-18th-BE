package com.muse.meomuneum.auth.exception;

import jakarta.servlet.http.HttpServletRequest;

import com.muse.meomuneum.global.exception.ErrorCode;
import com.muse.meomuneum.global.exception.GlobalErrorCode;

public final class AuthErrorCodeResolver {

    private static final String CSRF_TOKEN_PATH = "/api/v1/auth/token/csrf";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REFRESH_PATH = "/api/v1/auth/token/refresh";

    private AuthErrorCodeResolver() {
    }

    public static ErrorCode resolveInternalServerError(HttpServletRequest request) {
        return switch (request.getRequestURI()) {
            case LOGIN_PATH -> AuthErrorCode.LOGIN_INTERNAL_SERVER_ERROR;
            case REFRESH_PATH -> AuthErrorCode.REFRESH_INTERNAL_SERVER_ERROR;
            case CSRF_TOKEN_PATH -> AuthErrorCode.CSRF_REQUEST_FAILED;
            default -> GlobalErrorCode.INTERNAL_SERVER_ERROR;
        };
    }
}
