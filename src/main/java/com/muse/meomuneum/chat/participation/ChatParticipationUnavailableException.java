package com.muse.meomuneum.chat.participation;

public final class ChatParticipationUnavailableException extends RuntimeException {
    public ChatParticipationUnavailableException() {
        super("chat participation service unavailable", null, false, false);
    }
}
