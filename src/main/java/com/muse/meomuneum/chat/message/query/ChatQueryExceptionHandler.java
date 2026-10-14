package com.muse.meomuneum.chat.message.query;

import java.util.HashMap;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.response.ApiResponse;

@RestControllerAdvice(assignableTypes = ChatMessageQueryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChatQueryExceptionHandler {
    @ExceptionHandler(ChatRoomException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> denied(ChatRoomException exception) {
        Map<String, Object> data = new HashMap<>();
        data.put("code", exception.getErrorCode().code());
        if (exception.getBannedUntil() != null) {
            data.put("banned_until", exception.getBannedUntil());
        }
        return ResponseEntity.status(exception.getErrorCode().status())
                .body(ApiResponse.of(exception.getErrorCode().message(), data));
    }

    @ExceptionHandler(ChatQueryException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> invalid(ChatQueryException exception) {
        return ResponseEntity.status(exception.isNotFound() ? 404 : 400).body(ApiResponse.of(exception.getMessage(),
                Map.of("code", exception.isNotFound() ? "CHAT_MESSAGE_404_UNAVAILABLE" : "CHAT_QUERY_400_INVALID")));
    }

    @ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Map<String, String>>> malformed(Exception exception) {
        return invalid(new ChatQueryException(false));
    }
}
