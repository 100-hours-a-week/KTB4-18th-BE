package com.muse.meomuneum.global.security;

import java.util.function.Supplier;

/** Optional Core serialization boundary; the shared bearer filter must not import Core implementations. */
@FunctionalInterface
public interface AccessSessionGuard {
    AccessSessionAuthority.Decision verifySession(String sid, Supplier<AccessSessionAuthority.Decision> canonical);

    final class Unavailable extends RuntimeException {
        public Unavailable() {
            super("AUTH_STATE_UNAVAILABLE");
        }
    }
}
