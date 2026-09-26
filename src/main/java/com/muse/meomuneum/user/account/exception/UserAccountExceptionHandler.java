package com.muse.meomuneum.user.account.exception;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.user.account.controller.UserAccountController;

@RestControllerAdvice(assignableTypes = UserAccountController.class)
public class UserAccountExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UserAccountExceptionHandler.class);

    @ExceptionHandler(UserAccountException.class)
    public ResponseEntity<ApiResponse<Void>> handle(UserAccountException exception, HttpServletRequest request) {
        log.warn("event=user_account_request_failed domainCode={} httpStatus={} method={} path={} requestId={}",
                exception.getCode(), exception.getStatus().value(), request.getMethod(), request.getRequestURI(),
                MDC.get("requestId"));
        return ResponseEntity.status(exception.getStatus()).body(ApiResponse.failure(exception.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> invalidRequest(Exception exception, HttpServletRequest request) {
        log.warn("event=user_account_invalid_request httpStatus=400 method={} path={} requestId={} exceptionType={}",
                request.getMethod(), request.getRequestURI(), MDC.get("requestId"),
                exception.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(ApiResponse.failure("invalid request"));
    }
}
