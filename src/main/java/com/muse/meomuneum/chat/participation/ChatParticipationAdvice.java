package com.muse.meomuneum.chat.participation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.global.response.ApiResponse;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ChatParticipationAdvice {
    @ExceptionHandler(ChatParticipationUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(ChatParticipationUnavailableException failure) {
        return ResponseEntity.status(503).header("Cache-Control", "no-store").header("Retry-After", "2")
                .body(ApiResponse.failure("chat participation service unavailable"));
    }
}
