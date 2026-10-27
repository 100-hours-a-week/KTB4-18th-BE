package com.muse.meomuneum.chat.message.relay;

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

/** Strict reference-only wire contract; decode errors never contain payload data. */
public record ChatMessageRedisEvent(String eventId, String producerId, long epoch, long roomId, long roomSeq,
        long messageId, Instant occurredAt) {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final long MAX_ID = 9007199254740991L;

    public String encode() {
        try {
            return JSON.writeValueAsString(Map.of("schema_version", 1, "event_id", eventId,
                    "event_type", "CHAT_MESSAGE_CREATED", "producer_id", producerId,
                    "occurred_at", occurredAt.toString(), "epoch", epoch,
                    "payload", Map.of("room_id", roomId, "room_seq", roomSeq, "message_id", messageId)));
        } catch (Exception invalid) {
            throw new IllegalStateException("Chat message event encoding failed");
        }
    }

    public static ChatMessageRedisEvent decode(String channel, String prefix, String body) {
        try {
            if (body == null || body.length() > 16384 || body.getBytes(StandardCharsets.UTF_8).length > 16384) {
                throw new IllegalArgumentException();
            }
            JsonNode root = JSON.readTree(body);
            fields(root, Set.of("schema_version", "event_id", "event_type", "producer_id", "occurred_at", "epoch",
                    "payload"));
            if (!root.path("schema_version").isIntegralNumber() || !root.path("schema_version").canConvertToInt()
                    || root.path("schema_version").intValue() != 1
                    || !"CHAT_MESSAGE_CREATED".equals(root.path("event_type").textValue())) {
                throw new IllegalArgumentException();
            }
            String event = uuid(root.path("event_id").textValue());
            String producer = root.path("producer_id").textValue();
            if (producer == null || !producer.startsWith("chat:")) {
                throw new IllegalArgumentException();
            }
            uuid(producer.substring(5));
            String at = root.path("occurred_at").textValue();
            if (at == null || !at.endsWith("Z")) {
                throw new IllegalArgumentException();
            }
            JsonNode payload = root.path("payload");
            fields(payload, Set.of("room_id", "room_seq", "message_id"));
            long room = positive(payload.path("room_id"));
            if (!(prefix + room).equals(channel)) {
                throw new IllegalArgumentException();
            }
            return new ChatMessageRedisEvent(event, producer, positive(root.path("epoch")), room,
                    positive(payload.path("room_seq")), positive(payload.path("message_id")), Instant.parse(at));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid Chat message event");
        }
    }

    private static String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static long positive(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0
                || value.longValue() > MAX_ID) {
            throw new IllegalArgumentException();
        }
        return value.longValue();
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (!node.isObject()) {
            throw new IllegalArgumentException();
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException();
        }
    }
}
