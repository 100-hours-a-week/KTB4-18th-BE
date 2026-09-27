package com.muse.meomuneum.recommendation.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.recommendation.controller.RecommendationController;
import com.muse.meomuneum.recommendation.controller.UserRecommendationController;

@RestControllerAdvice(assignableTypes = {RecommendationController.class, UserRecommendationController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecommendationExceptionHandler {
    @ExceptionHandler(RecommendationException.class)
    public ResponseEntity<RecommendationController.ApiResponse<Void>> handle(RecommendationException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new RecommendationController.ApiResponse<>(exception.getMessage(), null));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<RecommendationController.ApiResponse<Void>> invalid(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new RecommendationController.ApiResponse<>("입력 내용과 요청 형식을 확인해 주세요. (최대 1000자)", null));
    }

}
