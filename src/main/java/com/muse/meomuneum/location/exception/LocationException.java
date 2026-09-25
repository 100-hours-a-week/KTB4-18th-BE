package com.muse.meomuneum.location.exception;

public class LocationException extends RuntimeException {

    private final LocationErrorCode errorCode;

    public LocationException(LocationErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public LocationErrorCode getErrorCode() {
        return errorCode;
    }
}
