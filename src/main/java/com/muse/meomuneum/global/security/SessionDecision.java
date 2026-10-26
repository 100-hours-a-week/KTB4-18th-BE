package com.muse.meomuneum.global.security;

public record SessionDecision(SessionPair pair, boolean active, long generation, long remainingMillis) {
    public static SessionDecision inactive(SessionPair pair) {
        return new SessionDecision(pair, false, 0, 0);
    }
}
