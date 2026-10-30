package com.muse.meomuneum.auth.exception;

import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.hazelcast.core.HazelcastException;
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
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception exception, HttpServletRequest request) {
        log.warn(
                "event=auth_request_invalid domainCode={} httpStatus={} method={} path={} requestId={} "
                        + "exceptionType={} invalidFields={}",
                AuthErrorCode.INVALID_REQUEST.code(), AuthErrorCode.INVALID_REQUEST.status().value(),
                request.getMethod(), request.getRequestURI(), MDC.get(REQUEST_ID_KEY),
                exception.getClass().getSimpleName(), invalidFields(exception));
        return toErrorResponse(AuthErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationFailure(AuthenticationFailedException exception,
            HttpServletRequest request) {
        ErrorCode errorCode = exception.getErrorCode();
        log.warn(
                "event=auth_authentication_failed domainCode={} httpStatus={} method={} path={} requestId={} "
                        + "exceptionType={}",
                errorCode.code(), errorCode.status().value(), request.getMethod(), request.getRequestURI(),
                MDC.get(REQUEST_ID_KEY), exception.getClass().getSimpleName());
        return toErrorResponse(errorCode);
    }

    @ExceptionHandler({AuthStateUnavailableException.class, AuthStateBusyException.class})
    public ResponseEntity<ApiResponse<Void>> handleAuthStateUnavailable(RuntimeException exception) {
        String code = exception instanceof AuthStateBusyException ? "AUTH_STATE_BUSY" : "AUTH_STATE_UNAVAILABLE";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, "1").body(ApiResponse.failure(code));
    }

    @ExceptionHandler(HazelcastException.class)
    public ResponseEntity<ApiResponse<Void>> handleHazelcastFailure() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiResponse.failure("AUTH_STATE_UNAVAILABLE"));
    }

    @ExceptionHandler(AuthStateTooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooManyRequests(AuthStateTooManyRequestsException exception) {
        HttpStatus status = "DEVICE_SESSION_LIMIT".equals(exception.code())
                ? HttpStatus.CONFLICT
                : HttpStatus.TOO_MANY_REQUESTS;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, "1").body(ApiResponse.failure(exception.code()));
    }

    @ExceptionHandler(AuthStateTooEarlyException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooEarly(AuthStateTooEarlyException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, "1").body(ApiResponse.failure(exception.code()));
    }

    @ExceptionHandler(AuthStateConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(AuthStateConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(ApiResponse.failure(exception.code()));
    }

    @ExceptionHandler(AuthFlowException.class)
    public ResponseEntity<ApiResponse<Void>> handleFlowFailure(AuthFlowException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status())
                .cacheControl(CacheControl.noStore());
        if (exception.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(ApiResponse.failure(exception.code()));
    }

    @ExceptionHandler(AuthStateExpiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleExpired() {
        return ResponseEntity.status(HttpStatus.GONE).cacheControl(CacheControl.noStore())
                .body(ApiResponse.failure("TICKET_EXPIRED"));
    }

    @ExceptionHandler(AuthStateNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(AuthStateNotFoundException exception) {
        HttpStatus status = "TICKET_UNKNOWN".equals(exception.code()) ? HttpStatus.NOT_FOUND : HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiResponse.failure(exception.code()));
    }

    private String invalidFields(Exception exception) {
        if (!(exception instanceof MethodArgumentNotValidException validationException)) {
            return UNKNOWN_FIELDS;
        }

        return validationException.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ":" + fieldError.getCode()).distinct().sorted()
                .collect(Collectors.joining(","));
    }

    private ResponseEntity<ApiResponse<Void>> toErrorResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.failure(errorCode.message()));
    }
}
