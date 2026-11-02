package com.muse.meomuneum.chat.runtime;

import java.util.UUID;

/** One identity per Chat process, shared by participation ownership and internal auth admission. */
public final class ChatTaskIdentity {
    private final String value = UUID.randomUUID().toString();

    public String value() {
        return value;
    }
}
