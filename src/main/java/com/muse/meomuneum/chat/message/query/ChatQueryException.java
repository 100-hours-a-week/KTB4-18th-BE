package com.muse.meomuneum.chat.message.query;

public class ChatQueryException extends RuntimeException {
    private final boolean notFound;

    public ChatQueryException(boolean notFound) {
        super(notFound ? "chat message unavailable" : "invalid chat query");
        this.notFound = notFound;
    }

    public boolean isNotFound() {
        return notFound;
    }
}
