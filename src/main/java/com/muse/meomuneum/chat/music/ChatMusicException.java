package com.muse.meomuneum.chat.music;

public class ChatMusicException extends RuntimeException {
    private final int status;
    private final String code;
    private final int retryAfter;

    public ChatMusicException(int status, String code) {
        this(status, code, 1);
    }

    public ChatMusicException(int status, String code, int retryAfter) {
        super(code);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public int retryAfter() {
        return retryAfter;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
