package com.muse.meomuneum.global.exception;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.global.response.ApiResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception,
            HttpServletRequest request) {
        log.error(
                "event=unexpected_server_error domainCode={} httpStatus={} method={} path={} requestId={} "
                        + "exceptionType={}",
                GlobalErrorCode.INTERNAL_SERVER_ERROR.code(),
                GlobalErrorCode.INTERNAL_SERVER_ERROR.status().value(),
                request.getMethod(),
                request.getRequestURI(),
                MDC.get("requestId"),
                exception.getClass().getSimpleName(),
                exception
        );
        return toErrorResponse(GlobalErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> toErrorResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.failure(errorCode.message()));
    }
}
