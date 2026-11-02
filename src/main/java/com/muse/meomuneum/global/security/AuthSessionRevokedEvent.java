package com.muse.meomuneum.global.security;

/** No access/refresh credentials; generation comes from the committed Core state. */
public record AuthSessionRevokedEvent(long userId, String sid, long generation) {
    /** A committed DB revocation without a Hazelcast snapshot is an invalidation hint, never an ACTIVE grant. */
    public static AuthSessionRevokedEvent invalidation(long userId, String sid) {
        new SessionPair(userId, sid);
        return new AuthSessionRevokedEvent(userId, sid, 0);
    }

    @Override
    public String toString() {
        return "AuthSessionRevokedEvent[redacted]";
    }
}
