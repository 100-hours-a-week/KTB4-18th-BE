package com.muse.meomuneum.locationrecommendation.exception;

public class LocationRecommendationException extends RuntimeException {
    private final int status;
    private final String reason;

    public LocationRecommendationException(int status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }
}
