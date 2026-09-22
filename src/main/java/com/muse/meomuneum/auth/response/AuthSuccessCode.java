package com.muse.meomuneum.auth.response;

import org.springframework.http.HttpStatus;

import com.muse.meomuneum.global.response.SuccessCode;

public enum AuthSuccessCode implements SuccessCode {

    LOGIN_SUCCESS("AUTH_200", HttpStatus.OK, "login success");

    private final String code;
    private final String message;
    private final HttpStatus status;

    AuthSuccessCode(String code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
