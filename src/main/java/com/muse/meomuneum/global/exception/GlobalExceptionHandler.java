package com.muse.meomuneum.global.exception;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MusicRecordException.class)
    public ResponseEntity<ApiResponse<Void>> handleMusicRecordException(MusicRecordException exception,
            HttpServletRequest request) {
        log.warn("event=music_record_invalid_request reason={} method={} path={} requestId={}",
                exception.reason(), request.getMethod(), request.getRequestURI(), MDC.get("requestId"));
        return ResponseEntity.status(exception.status()).body(ApiResponse.failure(exception.publicMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidBody(Exception exception, HttpServletRequest request) {
        log.warn("event=request_invalid reason={} method={} path={} requestId={}",
                exception.getClass().getSimpleName(), request.getMethod(), request.getRequestURI(),
                MDC.get("requestId"));
        String path = request.getRequestURI();
        String message = "invalid request";
        if ("/api/v1/music/search".equals(path)) {
            message = "search query required";
        } else if ("/api/v1/locations/resolve".equals(path)) {
            message = "invalid coordinates or location accuracy insufficient";
        } else if ("/api/v1/users/me/music-records".equals(path)) {
            message = "invalid cursor";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(message));
    }

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
