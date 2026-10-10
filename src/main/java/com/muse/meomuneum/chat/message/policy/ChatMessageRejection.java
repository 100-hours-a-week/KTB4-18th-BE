package com.muse.meomuneum.chat.message.policy;

public class ChatMessageRejection extends RuntimeException {
    private final String reason;
    private final long retryAfterMs;

    public ChatMessageRejection(String reason) {
        this(reason, 0);
    }

    public ChatMessageRejection(String reason, long retryAfterMs) {
        super(reason);
        this.reason = reason;
        this.retryAfterMs = retryAfterMs;
    }

    public String reason() {
        return reason;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }
}
