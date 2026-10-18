package com.muse.meomuneum.chat.message.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

class ChatCursorCodecTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private final ChatCursorCodec codec = new ChatCursorCodec(
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL),
            Clock.fixed(NOW, ZoneOffset.UTC), "test-only-cursor-key");

    @Test
    void historyRequiresItsSignedAsOfAndRetainsItAcrossPages() {
        var cursor = new ChatQueryCursor(1, 2, 3, "view", "history", 10, 0,
                NOW.plusSeconds(60).toEpochMilli(), "2026-10-18T00:00:00");
        assertEquals(cursor.next(5), codec.decode(codec.encode(cursor.next(5))));
        var legacy = new ChatQueryCursor(1, 2, 3, "view", "history", 10, 0,
                NOW.plusSeconds(60).toEpochMilli());
        assertThrows(ChatQueryException.class, () -> codec.decode(codec.encode(legacy)));
        var future = new ChatQueryCursor(1, 2, 3, "view", "history", 10, 0,
                NOW.plusSeconds(60).toEpochMilli(), "2026-10-18T00:00:01");
        assertThrows(ChatQueryException.class, () -> codec.decode(codec.encode(future)));
    }

    @Test
    void recoveryWithoutHistoryAsOfRemainsValidAndTamperingIsRejected() {
        var cursor = new ChatQueryCursor(1, 2, 3, "view", "recovery", 10, 0,
                NOW.plusSeconds(60).toEpochMilli());
        String signed = codec.encode(cursor);
        assertEquals(cursor, codec.decode(signed));
        assertThrows(ChatQueryException.class, () -> codec.decode("A" + signed.substring(1)));
    }
}
