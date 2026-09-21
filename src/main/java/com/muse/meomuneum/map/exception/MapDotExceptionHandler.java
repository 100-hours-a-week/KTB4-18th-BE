package com.muse.meomuneum.map.exception;

import com.muse.meomuneum.map.controller.MapDotController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MapDotController.class)
public class MapDotExceptionHandler {
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<MapDotController.ApiResponse<Void>> handleCatalogFailure(IllegalStateException exception) {
        return ResponseEntity.internalServerError()
                .body(new MapDotController.ApiResponse<>("map dots are unavailable", null));
    }
}
