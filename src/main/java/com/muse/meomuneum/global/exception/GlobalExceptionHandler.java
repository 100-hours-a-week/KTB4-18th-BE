package com.muse.meomuneum.global.exception;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthErrorCodeResolver;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.response.ApiResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(MethodArgumentNotValidException exception) {
        return toErrorResponse(AuthErrorCode.LOGIN_INVALID_REQUEST);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationFailure(AuthenticationFailedException exception) {
        return toErrorResponse(exception.getErrorCode());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleRejectedRequest(AccessDeniedException exception) {
        return toErrorResponse(GlobalErrorCode.ACCESS_DENIED);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception,
            HttpServletRequest request) {
        return toErrorResponse(AuthErrorCodeResolver.resolveInternalServerError(request));
    }

    private ResponseEntity<ApiResponse<Void>> toErrorResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.failure(errorCode.message()));
    }
}
