package com.muse.meomuneum.chat.auth;

public interface ChatSessionVerifier {
    void fresh(long userId, String sid);

    void assertValid(long userId, String sid);

    void invalidate(long userId, String sid);
}
