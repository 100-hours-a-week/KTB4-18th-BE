package com.muse.meomuneum.user.signup.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.user.signup.controller.SignupController;

@RestControllerAdvice(assignableTypes = SignupController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SignupExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SignupExceptionHandler.class);

    @ExceptionHandler({InvalidSignupRequestException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("invalid request"));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiResponse<Void>> duplicateEmail(DuplicateEmailException exception) {
        return ResponseEntity.status(409).body(ApiResponse.failure("email already exists"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        LOGGER.error("회원가입 요청 처리 중 예외가 발생했습니다.", exception);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.failure("internal server error"));
    }
}
