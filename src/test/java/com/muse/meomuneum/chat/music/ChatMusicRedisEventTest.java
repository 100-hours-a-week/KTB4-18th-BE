package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisProperties;

class ChatMusicRedisEventTest {
    private final ChatRedisChannels channels = new ChatRedisChannels(new ChatRedisProperties("test", "localhost", 6379,
            "default", "synthetic", false, Duration.ofSeconds(1), Duration.ofSeconds(1)));

    @Test
    void strictReferenceContractHasNoStoredPlaybackOrUserDataAndUsesSeparateChannel() {
        var event = event();
        assertThat(ChatMusicRedisEvent.decode(event.channel(channels), channels, "test", event.encode()))
                .isEqualTo(event);
        assertThat(event.encode()).doesNotContain("youtube", "user_id", "title", "snapshot");
        assertThat(event.channel(channels)).isEqualTo("test:chat:music:10");
        assertThat(event.channel(channels)).isNotEqualTo(channels.roomChannel(10));
    }

    @Test
    void rejectsUnknownDuplicateTrailingWrongNamespaceAndUnsafeNumbers() {
        var event = event();
        for (String body : java.util.List.of(event.encode() + " {}",
                event.encode().replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"),
                event.encode().replace("\"schema_version\":1", "\"schema_version\":4294967297"),
                event.encode().replace("\"music_version\":2", "\"music_version\":9007199254740992"),
                event.encode().replace("\"event_type\":\"QUEUE_UPDATED\"", "\"event_type\":\"PLAYBACK_SYNC\""),
                event.encode().replace("\"environment\":\"test\"", "\"environment\":\"other\""),
                event.encode().replace("\"room_id\":10", "\"room_id\":10,\"data\":{}"))) {
            assertThatThrownBy(() -> ChatMusicRedisEvent.decode(event.channel(channels), channels, "test", body))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ChatMusicRedisEvent.decode(channels.roomChannel(10), channels, "test", event.encode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ChatMusicRedisEvent event() {
        return new ChatMusicRedisEvent(UUID.randomUUID().toString(), "test", "chat:" + UUID.randomUUID(),
                1, 10, 3, 2, "QUEUE_UPDATED", Instant.parse("2026-11-09T01:00:00Z"));
    }
}
