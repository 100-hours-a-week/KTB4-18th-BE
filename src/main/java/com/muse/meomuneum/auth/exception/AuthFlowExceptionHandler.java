package com.muse.meomuneum.auth.exception;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.auth.controller.AuthFlowController;
import com.muse.meomuneum.auth.controller.LoginQueueController;
import com.muse.meomuneum.global.response.ApiResponse;

@RestControllerAdvice(assignableTypes = {AuthFlowController.class, LoginQueueController.class})
public class AuthFlowExceptionHandler {
    @ExceptionHandler(AuthFlowException.class)
    public ResponseEntity<ApiResponse<Void>> handleFlowFailure(AuthFlowException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status())
                .cacheControl(CacheControl.noStore());
        if (exception.status() == HttpStatus.SERVICE_UNAVAILABLE
                || exception.status() == HttpStatus.TOO_MANY_REQUESTS) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(ApiResponse.failure(exception.code()));
    }

    @ExceptionHandler(AuthStateUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiResponse.failure("AUTH_STATE_UNAVAILABLE"));
    }

    @ExceptionHandler(AuthStateConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(AuthStateConflictException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore());
        if (exception instanceof AuthStateOperationInProgressException) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(ApiResponse.failure(exception.code()));
    }
}
