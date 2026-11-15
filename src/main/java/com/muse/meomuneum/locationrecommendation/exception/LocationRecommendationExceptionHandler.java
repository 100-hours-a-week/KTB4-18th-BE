package com.muse.meomuneum.locationrecommendation.exception;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.global.response.ApiResponse;

@RestControllerAdvice(assignableTypes = com.muse.meomuneum.locationrecommendation.controller.LocationRecommendationController.class)
public class LocationRecommendationExceptionHandler {

    @ExceptionHandler(LocationRecommendationException.class)
    public ResponseEntity<ApiResponse<Void>> handle(LocationRecommendationException exception) {
        return failure(exception.status(), exception.reason());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidBody() {
        return failure(400, "INVALID_REQUEST");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader() {
        return failure(400, "INVALID_REQUEST");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType() {
        return failure(415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleInternalError() {
        return failure(500, "INTERNAL_ERROR");
    }

    private ResponseEntity<ApiResponse<Void>> failure(int status, String reason) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(ApiResponse.failure(reason));
    }
}
