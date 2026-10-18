package com.muse.meomuneum.chat.connection;

/** Published by authentication only after revoking the verified user/session pair. */
public record ChatSessionLogoutEvent(Long userId, String authSessionId) {
    public ChatSessionLogoutEvent {
        if (userId == null || !ChatAuthSessionRegistry.isValidSessionId(authSessionId)) {
            throw new IllegalArgumentException("A verified user and authentication session are required");
        }
    }
}
