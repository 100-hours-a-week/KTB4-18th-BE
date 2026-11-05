package com.muse.meomuneum.global.security;

/** No access/refresh credentials; generation comes from the committed Core state. */
public record AuthSessionRevokedEvent(long userId, String sid, long generation) {
    @Override
    public String toString() {
        return "AuthSessionRevokedEvent[redacted]";
    }
}
