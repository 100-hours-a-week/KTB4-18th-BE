package com.muse.meomuneum.global.security;

public record SessionPair(long userId, String sid) {
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public SessionPair {
        if (userId < 1 || userId > MAX_SAFE_INTEGER || !AuthSessionIds.isValid(sid)) {
            throw new IllegalArgumentException("invalid authentication context");
        }
    }

    @Override
    public String toString() {
        return "SessionPair[redacted]";
    }
}
