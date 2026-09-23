package com.muse.meomuneum.auth.exception;

import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.auth.controller.AuthController;
import com.muse.meomuneum.global.exception.ErrorCode;
import com.muse.meomuneum.global.response.ApiResponse;

@RestControllerAdvice(assignableTypes = AuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);
    private static final String REQUEST_ID_KEY = "requestId";
    private static final String UNKNOWN_FIELDS = "none";

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(
            Exception exception,
            HttpServletRequest request
    ) {
        log.warn(
                "event=auth_request_invalid domainCode={} httpStatus={} method={} path={} requestId={} "
                        + "exceptionType={} invalidFields={}",
                AuthErrorCode.INVALID_REQUEST.code(),
                AuthErrorCode.INVALID_REQUEST.status().value(),
                request.getMethod(),
                request.getRequestURI(),
                MDC.get(REQUEST_ID_KEY),
                exception.getClass().getSimpleName(),
                invalidFields(exception)
        );
        return toErrorResponse(AuthErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationFailure(
            AuthenticationFailedException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        log.warn(
                "event=auth_authentication_failed domainCode={} httpStatus={} method={} path={} requestId={} "
                        + "exceptionType={}",
                errorCode.code(),
                errorCode.status().value(),
                request.getMethod(),
                request.getRequestURI(),
                MDC.get(REQUEST_ID_KEY),
                exception.getClass().getSimpleName()
        );
        return toErrorResponse(errorCode);
    }

    private String invalidFields(Exception exception) {
        if (!(exception instanceof MethodArgumentNotValidException validationException)) {
            return UNKNOWN_FIELDS;
        }

        return validationException.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ":" + fieldError.getCode())
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
    }

    private ResponseEntity<ApiResponse<Void>> toErrorResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.failure(errorCode.message()));
    }
}
