package com.muse.meomuneum.chat.connection;

public record ChatPresenceChangedEvent(Long roomId, int count, long revision, String sessionId,
        String subscriptionId) {
}
