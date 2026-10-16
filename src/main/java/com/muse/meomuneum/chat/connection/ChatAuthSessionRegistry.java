package com.muse.meomuneum.chat.connection;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.global.security.TokenClaims;

/** Identity comes exclusively from the verified access token, never from a device header. */
@Component
public class ChatAuthSessionRegistry {
    private final Map<String, Context> connections = new ConcurrentHashMap<>();

    public Context resolve(TokenClaims claims, String requestedSessionId) {
        String sid = claims.sessionId();
        if (!isValidSessionId(sid) || (requestedSessionId != null && !sid.equals(requestedSessionId))) {
            throw new IllegalArgumentException("AUTH_REQUIRED");
        }
        return new Context(claims.userId(), sid);
    }

    public static boolean isValidSessionId(String sid) {
        try {
            return sid != null && UUID.fromString(sid).toString().equals(sid);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public void register(String connectionId, Context context) {
        if (connections.putIfAbsent(connectionId, context) != null) {
            throw new IllegalArgumentException("AUTH_REQUIRED");
        }
    }

    public Optional<Context> context(String connectionId) {
        return Optional.ofNullable(connections.get(connectionId));
    }

    public Set<String> connections(Long userId, String authSessionId) {
        Context owner = new Context(userId, authSessionId);
        return connections.entrySet().stream().filter(entry -> owner.equals(entry.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    public void remove(String connectionId) {
        connections.remove(connectionId);
    }

    public record Context(Long userId, String authSessionId) {
    }
}
