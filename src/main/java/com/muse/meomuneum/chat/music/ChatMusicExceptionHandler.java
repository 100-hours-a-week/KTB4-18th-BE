package com.muse.meomuneum.chat.music;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.response.ApiResponse;

@RestControllerAdvice(assignableTypes = {ChatMusicQueueController.class, ChatMusicPlaybackController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChatMusicExceptionHandler {
    @ExceptionHandler(ChatMusicException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> music(ChatMusicException error) {
        var response = ResponseEntity.status(error.status()).header("Cache-Control", "no-store");
        if (error.status() == 429) {
            response.header("Retry-After", Integer.toString(error.retryAfter()));
        }
        return response.body(ApiResponse.of(error.getMessage(), Map.of("code", error.code())));
    }

    @ExceptionHandler(ChatRoomException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> participation(ChatRoomException error) {
        return ResponseEntity.status(error.getErrorCode().status()).header("Cache-Control", "no-store")
                .body(ApiResponse.of(error.getMessage(), Map.of("code", error.getErrorCode().code())));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Map<String, String>>> invalid(Exception error) {
        return music(new ChatMusicException(400, "INVALID_REQUEST"));
    }
}
