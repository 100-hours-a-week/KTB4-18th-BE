package com.muse.meomuneum.chat.control;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.chat.message.relay.ChatRedisEvent;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;

/** Terminal hints never grant rights, mutate Core authentication, or carry message/token contents. */
public record ChatControlEvent(String eventId, String type, String producerId, Instant occurredAt,
        long epoch, Map<String, Object> payload) implements ChatRedisEvent {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> TYPES = Set.of("CHAT_VIEW_ENDED", "CHAT_MEMBERSHIP_ENDED",
            "CHAT_PRESENCE_CHANGED");
    public static final Set<String> REASONS = Set.of("LEFT", "REGION_CHANGED", "SESSION_REVOKED", "ACCESS_RESTRICTED");

    public ChatControlEvent {
        payload = Map.copyOf(payload);
    }

    @Override
    public long roomId() {
        return (long) payload.get("room_id");
    }

    @Override
    public long roomSeq() {
        return (long) payload.get("room_seq");
    }

    @Override
    public String channel(ChatRedisChannels channels) {
        return type.equals("CHAT_PRESENCE_CHANGED") ? channels.roomChannel(roomId()) : channels.controlChannel();
    }

    @Override
    public String encode() {
        try {
            // Stable canonical JSON permits exact committed-body verification across JVMs.
            var body = new TreeMap<String, Object>();
            body.put("schema_version", 1);
            body.put("event_id", eventId);
            body.put("event_type", type);
            body.put("producer_id", producerId);
            body.put("occurred_at", occurredAt.toString());
            body.put("epoch", epoch);
            body.put("payload", new TreeMap<>(payload));
            return JSON.writeValueAsString(body);
        } catch (Exception invalid) {
            throw new IllegalStateException("Chat control encoding failed");
        }
    }

    public static boolean isControl(String body) {
        try {
            return TYPES.contains(tree(body).path("event_type").asText());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static boolean isCoreAuth(String body) {
        try {
            return "AUTH_SESSION_REVOKED".equals(tree(body).path("event_type").asText());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static ChatControlEvent decode(String body) {
        try {
            JsonNode root = tree(body);
            fields(root, Set.of("schema_version", "event_id", "event_type", "producer_id", "occurred_at", "epoch",
                    "payload"));
            if (!root.path("schema_version").isIntegralNumber() || !root.path("schema_version").canConvertToInt()
                    || root.path("schema_version").intValue() != 1) {
                throw new IllegalArgumentException();
            }
            String type = root.path("event_type").textValue();
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException();
            }
            String producer = root.path("producer_id").textValue();
            if (producer == null || !producer.startsWith("chat:")) {
                throw new IllegalArgumentException();
            }
            uuid(producer.substring(5));
            String at = root.path("occurred_at").textValue();
            if (at == null || !at.endsWith("Z")) {
                throw new IllegalArgumentException();
            }
            JsonNode data = root.path("payload");
            var expected = new HashSet<>(Set.of("room_id", "room_seq"));
            if (!type.equals("CHAT_PRESENCE_CHANGED")) {
                expected.addAll(Set.of("user_id", "sid", "membership_id", "reason"));
                if (type.equals("CHAT_VIEW_ENDED")) {
                    expected.add("view_id");
                }
            }
            fields(data, expected);
            var payload = new TreeMap<String, Object>();
            for (String key : expected) {
                if (Set.of("room_id", "room_seq", "user_id", "membership_id").contains(key)) {
                    payload.put(key, positive(data.path(key)));
                } else if (key.equals("sid") || key.equals("view_id")) {
                    payload.put(key, uuid(data.path(key).textValue()));
                } else {
                    String reason = data.path(key).textValue();
                    Set<String> allowed = type.equals("CHAT_MEMBERSHIP_ENDED")
                            ? Set.of("LAST_VIEW_LEFT", "REGION_CHANGED", "SESSION_REVOKED", "ACCESS_RESTRICTED")
                            : REASONS;
                    if (!allowed.contains(reason)) {
                        throw new IllegalArgumentException();
                    }
                    payload.put(key, reason);
                }
            }
            return new ChatControlEvent(uuid(root.path("event_id").textValue()), type, producer, Instant.parse(at),
                    positive(root.path("epoch")), payload);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Chat control event");
        }
    }

    private static JsonNode tree(String body) {
        try {
            if (body == null || body.length() > 16384 || body.getBytes(StandardCharsets.UTF_8).length > 16384) {
                throw new IllegalArgumentException();
            }
            return JSON.readTree(body);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid Chat control event");
        }
    }

    private static long positive(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0
                || value.longValue() > 9007199254740991L) {
            throw new IllegalArgumentException();
        }
        return value.longValue();
    }

    private static String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException();
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException();
        }
    }
}
