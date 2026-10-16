package com.muse.meomuneum.auth.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.hazelcast.core.HazelcastException;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.user.account.controller.UserAccountController;

@RestControllerAdvice(assignableTypes = UserAccountController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthStateUserAccountExceptionHandler {

    @ExceptionHandler({AuthStateUnavailableException.class, AuthStateBusyException.class})
    public ResponseEntity<ApiResponse<Void>> handleUnavailable(RuntimeException exception) {
        String code = exception instanceof AuthStateBusyException ? "AUTH_STATE_BUSY" : "AUTH_STATE_UNAVAILABLE";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.failure(code));
    }

    @ExceptionHandler(HazelcastException.class)
    public ResponseEntity<ApiResponse<Void>> handleHazelcastFailure() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.failure("AUTH_STATE_UNAVAILABLE"));
    }
}
