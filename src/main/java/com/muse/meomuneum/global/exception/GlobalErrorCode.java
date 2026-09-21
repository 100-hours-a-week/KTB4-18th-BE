package com.muse.meomuneum.global.exception;

import org.springframework.http.HttpStatus;

public enum GlobalErrorCode implements ErrorCode {


    // 인증이 필요한 모든 보호 API.
    ACCESS_UNAUTHORIZED(
            "GLOBAL_ACCESS_401", HttpStatus.UNAUTHORIZED, "unauthorized"
    ),

    // 거부하는 모든 API.
    ACCESS_DENIED(
            "GLOBAL_ACCESS_403", HttpStatus.FORBIDDEN, "request rejected"
    ),

    // 명세에 개별 500 응답이 없는 API의 처리되지 않은 예외.
    INTERNAL_SERVER_ERROR(
            "GLOBAL_500", HttpStatus.INTERNAL_SERVER_ERROR, "internal server error"
    );

    private final String code;
    private final String message;
    private final HttpStatus status;

    GlobalErrorCode(String code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
