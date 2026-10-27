package com.muse.meomuneum.chat.message.relay;

import java.time.Instant;

import com.muse.meomuneum.chat.redis.ChatRedisChannels;

/** Committed references share a room claim; their consumers retain distinct authorization rules. */
public interface ChatRedisEvent {
    String eventId();
    String producerId();
    long epoch();
    long roomId();
    long roomSeq();
    Instant occurredAt();
    String encode();
    String channel(ChatRedisChannels channels);
}
