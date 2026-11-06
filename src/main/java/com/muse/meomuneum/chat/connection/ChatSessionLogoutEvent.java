package com.muse.meomuneum.chat.connection;

import com.muse.meomuneum.global.security.AuthSessionIds;

/** Published by authentication only after revoking the verified user/session pair. */
public record ChatSessionLogoutEvent(Long userId, String authSessionId) {
    public ChatSessionLogoutEvent {
        if (userId == null || !AuthSessionIds.isValid(authSessionId)) {
            throw new IllegalArgumentException("A verified user and authentication session are required");
        }
    }
}
