package com.muse.meomuneum.location.exception;

import org.springframework.http.HttpStatus;

import com.muse.meomuneum.global.exception.ErrorCode;

public enum LocationErrorCode implements ErrorCode {

    INVALID_COORDINATES(
            "LOCATION_400_INVALID_COORDINATES",
            HttpStatus.BAD_REQUEST,
            "invalid coordinates or location accuracy insufficient"
    ),
    INVALID_LOCATION_TOKEN(
            "LOCATION_400_INVALID_TOKEN",
            HttpStatus.BAD_REQUEST,
            "invalid location resolution token"
    ),
    REVERSE_GEOCODING_FAILED(
            "LOCATION_502_REVERSE_GEOCODING_FAILED",
            HttpStatus.BAD_GATEWAY,
            "reverse geocoding failed"
    );

    private final String code;
    private final HttpStatus status;
    private final String message;

    LocationErrorCode(String code, HttpStatus status, String message) {
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
