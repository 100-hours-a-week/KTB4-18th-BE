package com.muse.meomuneum.global.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class AuthRevocationProtocol {
    private static final Set<String> REASONS = Set.of("LOGOUT", "SESSION_REVOKED", "PASSWORD_CHANGED",
            "REFRESH_REUSE", "SESSION_EXPIRED", "USER_INACTIVE");

    private AuthRevocationProtocol() {
    }

    public static String encode(AuthSessionRevokedEvent event, String producer) {
        if (!validProducer(producer) || event.generation() < 0 || event.generation() > SessionPair.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("invalid authentication event");
        }
        new SessionPair(event.userId(), event.sid());
        try {
            return new ObjectMapper().writeValueAsString(Map.of("schema_version", 1,
                    "event_id", UUID.randomUUID().toString(), "event_type", "AUTH_SESSION_REVOKED",
                    "producer_id", producer, "occurred_at", Instant.now().toString(),
                    "payload", Map.of("user_id", event.userId(), "sid", event.sid(),
                            "session_generation", event.generation(), "reason", "SESSION_REVOKED")));
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("invalid authentication event");
        }
    }

    public static AuthSessionRevokedEvent decode(String body, Set<String> producers) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16384) {
            throw new IllegalArgumentException("invalid authentication event");
        }
        var root = InternalSessionProtocol.tree(bytes);
        InternalSessionProtocol.fields(root, "schema_version", "event_id", "event_type", "producer_id", "occurred_at",
                "payload");
        if (InternalSessionProtocol.number(root, "schema_version", 1) != 1
                || !"AUTH_SESSION_REVOKED".equals(root.path("event_type").asText())
                || !AuthSessionIds.isValid(root.path("event_id").asText())
                || !validProducer(root.path("producer_id").asText())
                || !producers.contains(root.path("producer_id").asText())
                || !root.path("occurred_at").asText().endsWith("Z")) {
            throw new IllegalArgumentException("invalid authentication event");
        }
        Instant.parse(root.path("occurred_at").asText());
        var payload = root.get("payload");
        InternalSessionProtocol.fields(payload, "user_id", "sid", "session_generation", "reason");
        SessionPair pair = new SessionPair(InternalSessionProtocol.number(payload, "user_id", 1),
                payload.path("sid").asText());
        if (!REASONS.contains(payload.path("reason").asText())) {
            throw new IllegalArgumentException("invalid authentication event");
        }
        return new AuthSessionRevokedEvent(pair.userId(), pair.sid(),
                InternalSessionProtocol.number(payload, "session_generation", 0));
    }

    public static boolean validProducer(String value) {
        return value != null && value.startsWith("core:") && AuthSessionIds.isValid(value.substring(5));
    }
}
