package com.muse.meomuneum.recommendation.exception;

public class RecommendationException extends RuntimeException {
    private final int status;
    public RecommendationException(int status, String message) {
        super(message);
        this.status = status;
    }
    public int getStatus() {
        return status;
    }
}
