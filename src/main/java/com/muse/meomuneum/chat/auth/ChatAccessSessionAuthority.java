package com.muse.meomuneum.chat.auth;

import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.security.AccessSessionAuthority;

/** A fresh remote decision for bearer requests, without loading Core's local authentication state. */
public final class ChatAccessSessionAuthority implements AccessSessionAuthority {
    private final ChatSessionVerifier verifier;

    public ChatAccessSessionAuthority(ChatSessionVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Decision verifySession(Long userId, String sid) {
        try {
            verifier.fresh(userId, sid);
            return Decision.ACTIVE;
        } catch (ChatAuthUnavailableException unavailable) {
            return Decision.UNAVAILABLE;
        } catch (ChatRoomException inactive) {
            if (inactive.getErrorCode() != ChatRoomErrorCode.AUTH_REQUIRED) {
                throw inactive;
            }
            return Decision.REJECTED;
        }
    }
}
