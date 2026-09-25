package com.muse.meomuneum.musicrecord.exception;

import org.springframework.http.HttpStatus;

public class MusicRecordException extends RuntimeException {
    private final String reason;
    private final HttpStatus status;
    private final String publicMessage;

    public MusicRecordException(String reason) {
        this(reason, HttpStatus.BAD_REQUEST, "invalid request");
    }

    public MusicRecordException(String reason, HttpStatus status, String publicMessage) {
        super(publicMessage);
        this.reason = reason;
        this.status = status;
        this.publicMessage = publicMessage;
    }

    public String reason() { return reason; }
    public HttpStatus status() { return status; }
    public String publicMessage() { return publicMessage; }
}
