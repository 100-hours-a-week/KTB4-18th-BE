package com.muse.meomuneum.chat.room.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.chat.room.controller.ChatRoomController;
import com.muse.meomuneum.global.exception.ErrorCode;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.location.exception.LocationErrorCode;
import com.muse.meomuneum.location.exception.LocationException;

@RestControllerAdvice(assignableTypes = ChatRoomController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChatRoomExceptionHandler {

    @ExceptionHandler(ChatRoomException.class)
    public ResponseEntity<ApiResponse<Void>> handleChatRoomException(ChatRoomException exception) {
        return toErrorResponse(exception.getErrorCode());
    }

    @ExceptionHandler(LocationException.class)
    public ResponseEntity<ApiResponse<Void>> handleLocationException(LocationException exception) {
        return toErrorResponse(exception.getErrorCode());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return toErrorResponse(LocationErrorCode.INVALID_LOCATION_TOKEN);
    }

    private ResponseEntity<ApiResponse<Void>> toErrorResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.failure(errorCode.message()));
    }
}
