package com.muse.meomuneum.recommendation.exception;

public class SpeechTranscriptionException extends RuntimeException {
    private final int status;

    public SpeechTranscriptionException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
