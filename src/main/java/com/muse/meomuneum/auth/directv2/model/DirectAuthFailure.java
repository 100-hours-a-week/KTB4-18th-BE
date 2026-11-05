package com.muse.meomuneum.auth.directv2.model;

public class DirectAuthFailure extends RuntimeException {
    private final int status;

    public DirectAuthFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static DirectAuthFailure unavailable() {
        return new DirectAuthFailure(503, "AUTH_STATE_UNAVAILABLE");
    }

    public static DirectAuthFailure conflict(String code) {
        return new DirectAuthFailure(409, code);
    }
}
