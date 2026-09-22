package com.muse.meomuneum.user.signup.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.user.signup.controller.SignupController;

@RestControllerAdvice(assignableTypes = SignupController.class)
public class SignupExceptionHandler {
    @ExceptionHandler({InvalidSignupRequestException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<SignupController.ApiResponse<Void>> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new SignupController.ApiResponse<>("invalid request", null));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<SignupController.ApiResponse<Void>> duplicateEmail(DuplicateEmailException exception) {
        return ResponseEntity.status(409).body(new SignupController.ApiResponse<>("email already exists", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SignupController.ApiResponse<Void>> unexpected(Exception exception) {
        return ResponseEntity.internalServerError()
                .body(new SignupController.ApiResponse<>("internal server error", null));
    }
}
