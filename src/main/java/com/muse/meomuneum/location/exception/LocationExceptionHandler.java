package com.muse.meomuneum.location.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.location.controller.LocationController;

@RestControllerAdvice(assignableTypes = LocationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocationExceptionHandler {

    @ExceptionHandler(LocationException.class)
    public ResponseEntity<ApiResponse<Void>> handleLocationException(LocationException exception) {
        LocationErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.failure(errorCode.message()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        LocationErrorCode errorCode = LocationErrorCode.INVALID_COORDINATES;
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.failure(errorCode.message()));
    }
}
