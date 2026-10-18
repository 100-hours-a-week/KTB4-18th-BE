package com.muse.meomuneum.chat.connection;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.config.JwtProperties;

/** Prevents reconnecting a chat session after its trusted logout notification. */
@Component
public class ChatSessionAccessPolicy {
    private final Clock clock;
    private final long tokenLifetimeSeconds;
    private final Map<ChatAuthSessionRegistry.Context, Instant> revoked = new ConcurrentHashMap<>();

    public ChatSessionAccessPolicy(Clock clock, JwtProperties jwt) {
        this.clock = clock;
        this.tokenLifetimeSeconds = jwt.accessTokenExpirationSeconds();
    }

    public void authorize(Long userId, String sid) {
        if (!ChatAuthSessionRegistry.isValidSessionId(sid)) {
            throw denied();
        }
        Instant until = revoked.get(new ChatAuthSessionRegistry.Context(userId, sid));
        if (until != null && until.isAfter(clock.instant())) {
            throw denied();
        }
    }

    public void revoke(Long userId, String sid) {
        revoked.put(new ChatAuthSessionRegistry.Context(userId, sid),
                clock.instant().plusSeconds(tokenLifetimeSeconds + 60));
    }

    @Scheduled(fixedDelay = 60000)
    public void purgeExpiredRevocations() {
        revoked.values().removeIf(expiry -> !expiry.isAfter(clock.instant()));
    }

    private ChatRoomException denied() {
        return new ChatRoomException(ChatRoomErrorCode.AUTH_REQUIRED);
    }
}
