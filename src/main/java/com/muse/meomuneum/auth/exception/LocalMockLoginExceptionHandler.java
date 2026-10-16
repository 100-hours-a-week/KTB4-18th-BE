package com.muse.meomuneum.auth.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.auth.controller.LocalMockLoginController;
import com.muse.meomuneum.global.response.ApiResponse;

@RestControllerAdvice(assignableTypes = LocalMockLoginController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalMockLoginExceptionHandler {

    @ExceptionHandler(LocalMockLoginUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(ApiResponse.failure("LOCAL_MOCK_LOGIN_UNAVAILABLE"));
    }

    @ExceptionHandler(LocalMockLoginForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                .body(ApiResponse.failure("LOCAL_MOCK_LOGIN_FORBIDDEN"));
    }
}
