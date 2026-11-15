package com.muse.meomuneum.chat.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ChatTestSessions {
    private ChatTestSessions() {
    }

    public static String sid(Long userId) {
        return UUID.nameUUIDFromBytes(("chat-test-" + userId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
