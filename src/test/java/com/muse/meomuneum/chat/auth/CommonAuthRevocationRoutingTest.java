package com.muse.meomuneum.chat.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.muse.meomuneum.auth.internal.CoreAuthRevocationPublisher;
import com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;
import com.muse.meomuneum.global.security.AuthRevocationProtocol;
import com.muse.meomuneum.global.security.AuthSessionRevokedEvent;

class CommonAuthRevocationRoutingTest {
    @Test
    void commonEventUsesCorePublisherAndSidScopedLocalCompatibilityBridge() {
        var redis = mock(ChatRedisConnection.class, RETURNS_DEEP_STUBS);
        var commands = redis.commands().sync();
        var entry = mock(ChatRoomEntryService.class);
        String producer = "core:" + UUID.randomUUID();
        String channel = "test:chat:control:v1";
        String sid = UUID.randomUUID().toString();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ChatLocalAuthRevocationBridge.class, () -> new ChatLocalAuthRevocationBridge(entry));
            context.registerBean(CoreAuthRevocationPublisher.class,
                    () -> new CoreAuthRevocationPublisher(() -> redis, channel, producer));
            context.refresh();
            context.publishEvent(AuthSessionRevokedEvent.invalidation(7L, sid));
            verify(entry).loggedOut(new ChatSessionLogoutEvent(7L, sid));
            var body = ArgumentCaptor.forClass(String.class);
            verify(commands, timeout(2000)).publish(org.mockito.ArgumentMatchers.eq(channel),
                    body.capture());
            var event = AuthRevocationProtocol.decode(body.getValue(), Set.of(producer));
            assertEquals(7L, event.userId());
            assertEquals(sid, event.sid());
            assertEquals(0L, event.generation());
        }
    }
}
