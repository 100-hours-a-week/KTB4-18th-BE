package com.muse.meomuneum.user.signup.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.user.signup.controller.UserAvailabilityController;

@RestControllerAdvice(assignableTypes = UserAvailabilityController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UserAvailabilityExceptionHandler {
    @ExceptionHandler(InvalidAvailabilityRequestException.class)
    public ResponseEntity<ApiResponse<Void>> invalidAvailabilityRequest(
            InvalidAvailabilityRequestException exception) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.failure("invalid query parameter"));
    }
}
