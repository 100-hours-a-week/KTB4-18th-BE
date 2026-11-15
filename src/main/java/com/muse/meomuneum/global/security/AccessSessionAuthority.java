package com.muse.meomuneum.global.security;

/** App-neutral session decision; implementations belong to Core or the Chat internal-API adapter. */
@FunctionalInterface
public interface AccessSessionAuthority {
    enum Decision {
        ACTIVE, REJECTED, UNAVAILABLE
    }

    Decision verifySession(Long userId, String sid);
}
