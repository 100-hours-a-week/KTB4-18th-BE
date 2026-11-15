package com.muse.meomuneum.recommendation.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.muse.meomuneum.recommendation.controller.ImageRecommendationController;
import com.muse.meomuneum.recommendation.controller.RecommendationController;

@RestControllerAdvice(assignableTypes = ImageRecommendationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImageRecommendationExceptionHandler {
    @ExceptionHandler(RecommendationException.class)
    public ResponseEntity<RecommendationController.ApiResponse<Void>> recommendation(
            RecommendationException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new RecommendationController.ApiResponse<>(exception.getMessage(), null));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<RecommendationController.ApiResponse<Void>> missing(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new RecommendationController.ApiResponse<>("invalid image recommendation request", null));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<RecommendationController.ApiResponse<Void>> invalid(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new RecommendationController.ApiResponse<>("invalid image recommendation request", null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<RecommendationController.ApiResponse<Void>> tooLarge(Exception exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new RecommendationController.ApiResponse<>("image request too large", null));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<RecommendationController.ApiResponse<Void>> invalidMultipart(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new RecommendationController.ApiResponse<>("invalid image recommendation request", null));
    }
}
