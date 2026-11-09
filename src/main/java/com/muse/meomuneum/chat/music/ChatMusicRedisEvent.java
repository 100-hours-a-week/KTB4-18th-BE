package com.muse.meomuneum.chat.music;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;

/** Reference only; a received body never replaces canonical playback or grants participation. */
public record ChatMusicRedisEvent(String eventId, String environment, String producerId, long epoch, long roomId,
        long roomSeq, long musicVersion, String type, Instant occurredAt) {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public String channel(ChatRedisChannels channels) {
        return channels.musicChannel(roomId);
    }

    public String encode() {
        try {
            return JSON.writeValueAsString(Map.of("schema_version", 1, "event_id", eventId, "environment", environment,
                    "producer_id", producerId, "epoch", epoch, "room_id", roomId, "room_seq", roomSeq,
                    "music_version", musicVersion, "event_type", type, "occurred_at", occurredAt.toString()));
        } catch (Exception invalid) {
            throw new IllegalStateException("Music event encoding failed");
        }
    }

    public static ChatMusicRedisEvent decode(String channel, ChatRedisChannels channels, String environment,
            String body) {
        try {
            if (body == null || body.length() > 16384 || body.getBytes(StandardCharsets.UTF_8).length > 16384) {
                throw new IllegalArgumentException();
            }
            JsonNode node = JSON.readTree(body);
            Set<String> actual = new HashSet<>();
            node.fieldNames().forEachRemaining(actual::add);
            if (!node.isObject() || !actual.equals(Set.of("schema_version", "event_id", "environment", "producer_id",
                    "epoch", "room_id", "room_seq", "music_version", "event_type", "occurred_at"))
                    || !node.path("schema_version").isIntegralNumber() || !node.path("schema_version").canConvertToInt()
                    || node.path("schema_version").intValue() != 1
                    || !environment.equals(node.path("environment").textValue())) {
                throw new IllegalArgumentException();
            }
            String id = uuid(node.path("event_id").textValue());
            String producer = node.path("producer_id").textValue();
            if (producer == null || !producer.startsWith("chat:")) {
                throw new IllegalArgumentException();
            }
            uuid(producer.substring(5));
            String type = node.path("event_type").textValue();
            if (!Set.of("QUEUE_UPDATED", "PLAYBACK_UPDATED").contains(type)) {
                throw new IllegalArgumentException();
            }
            long room = positive(node.path("room_id"));
            String at = node.path("occurred_at").textValue();
            if (!channels.musicChannel(room).equals(channel) || at == null || !at.endsWith("Z")) {
                throw new IllegalArgumentException();
            }
            return new ChatMusicRedisEvent(id, environment, producer, positive(node.path("epoch")), room,
                    positive(node.path("room_seq")), positive(node.path("music_version")), type, Instant.parse(at));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid music event");
        }
    }

    private static String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static long positive(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1
                || node.longValue() > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException();
        }
        return node.longValue();
    }
}
