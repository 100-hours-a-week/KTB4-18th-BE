package com.muse.meomuneum.recommendation.exception;

import com.muse.meomuneum.recommendation.controller.RecommendationController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RecommendationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecommendationExceptionHandler {
    @ExceptionHandler(RecommendationException.class)
    public ResponseEntity<RecommendationController.ApiResponse<Void>> handle(RecommendationException exception) {
        return ResponseEntity.status(exception.getStatus()).body(new RecommendationController.ApiResponse<>(exception.getMessage(), null));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<RecommendationController.ApiResponse<Void>> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new RecommendationController.ApiResponse<>("입력 내용과 요청 형식을 확인해 주세요. (최대 1000자)", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RecommendationController.ApiResponse<Void>> unexpected(Exception exception) {
        // 입력 문장이 예외 로그에 포함되지 않도록 원문·exception 메시지는 기록하지 않습니다.
        return ResponseEntity.internalServerError().body(new RecommendationController.ApiResponse<>("추천 결과를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.", null));
    }
}
