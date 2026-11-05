package com.muse.meomuneum.chat.auth;

public final class ChatAuthUnavailableException extends RuntimeException {
    public ChatAuthUnavailableException() {
        super("chat authentication service unavailable");
    }
}
