package com.muse.meomuneum.chat.auth;

import com.muse.meomuneum.global.security.SessionPair;

/** Per operation context; never carried into a different HTTP request or user. */
public final class ChatAuthOperation implements AutoCloseable {
    private static final ThreadLocal<ChatAuthOperation> CURRENT = new ThreadLocal<>();
    private final ChatAuthOperation previous;
    private final ChatSessionVerifier verifier;
    private final SessionPair pair;
    private final java.time.Instant tokenExpiry;

    public ChatAuthOperation(ChatSessionVerifier verifier, SessionPair pair) {
        this(verifier, pair, java.time.Instant.MAX);
    }

    public ChatAuthOperation(ChatSessionVerifier verifier, SessionPair pair, java.time.Instant tokenExpiry) {
        this.tokenExpiry = tokenExpiry;
        this.verifier = verifier;
        this.pair = pair;
        previous = CURRENT.get();
        verify();
        CURRENT.set(this);
    }

    public static void check() {
        ChatAuthOperation scope = CURRENT.get();
        if (scope != null) {
            scope.verify();
        }
    }

    private void verify() {
        if (tokenExpiry == null || !tokenExpiry.isAfter(java.time.Instant.now())) {
            throw new com.muse.meomuneum.chat.room.exception.ChatRoomException(
                    com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode.AUTH_REQUIRED);
        }
        verifier.assertValid(pair.userId(), pair.sid());
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
