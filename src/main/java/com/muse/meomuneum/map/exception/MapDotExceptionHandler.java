package com.muse.meomuneum.map.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.map.controller.MapDotController;

@RestControllerAdvice(assignableTypes = MapDotController.class)
public class MapDotExceptionHandler {
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleCatalogFailure(IllegalStateException exception) {
        return ResponseEntity.internalServerError().body(new ApiResponse<>("map dots are unavailable", null));
    }
}
