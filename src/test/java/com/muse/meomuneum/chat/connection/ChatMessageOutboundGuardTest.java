package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Clock;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;

class ChatMessageOutboundGuardTest {
    @Test
    void dropsQueuedEventsForNewOrDepartedSubscribers() {
        ChatPresenceRegistry presence = new ChatPresenceRegistry(Clock.systemUTC());
        ChatMessageOutboundGuard guard = new ChatMessageOutboundGuard(presence);
        presence.connect(1L, 10L, 100L, 25, "first");
        presence.subscribed("first", "room", "/topic/chat-rooms/10");
        Set<String> acceptedRecipients = presence.recipients(10L);
        presence.connect(2L, 10L, 200L, 25, "later");
        presence.subscribed("later", "room", "/topic/chat-rooms/10");
        Message<byte[]> first = outgoing("first", acceptedRecipients);
        assertSame(first, guard.preSend(first, null));
        assertNull(guard.preSend(outgoing("later", acceptedRecipients), null));
        presence.unsubscribed("first", "room");
        assertNull(guard.preSend(first, null));
        presence.subscribed("first", "room", "/topic/chat-rooms/10");
        presence.remove(1L, 100L);
        assertNull(guard.preSend(first, null));
    }

    @Test
    void canonicalMessagePermissionDoesNotRepeatDbWorkUnderTheLocalPresenceMonitor() throws Exception {
        var service = org.mockito.Mockito.mock(
                com.muse.meomuneum.chat.participation.ChatParticipationService.class);
        var presence = new ChatPresenceRegistry(Clock.systemUTC());
        presence.connect(1L, 10L, 100L, 25, "first");
        presence.connect(2L, 10L, 200L, 25, "other");
        presence.subscribed("first", "room", "/topic/chat-rooms/10");
        presence.subscribed("other", "room", "/topic/chat-rooms/10");
        presence.setParticipation(service);
        var registry = new ChatAuthSessionRegistry();
        registry.register("first", new ChatAuthSessionRegistry.Context(1L, java.util.UUID.randomUUID().toString()));
        var guard = new ChatMessageOutboundGuard(presence);
        var deliveryMetrics = new com.muse.meomuneum.chat.message.relay.ChatDeliveryMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        guard.setDeliveryMetrics(deliveryMetrics);
        presence.setDeliveryMetrics(deliveryMetrics);
        guard.setParticipation(service);
        guard.setCoreVerifier(org.mockito.Mockito.mock(com.muse.meomuneum.chat.auth.ChatSessionVerifier.class),
                registry);
        org.mockito.Mockito.when(service.permitsMessageEnqueue(1L, "first", 10L, 99L, null)).thenReturn(true);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(service.socketValid(1L, 100L, "first")).thenAnswer(call -> {
            entered.countDown();
            org.junit.jupiter.api.Assertions.assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return true;
        });
        var frame = MessageBuilder.fromMessage(outgoing("first", Set.of("first")))
                .setHeader("chatMessageId", 99L).build();
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = workers.submit(() -> guard.preSend(frame, null));
            try {
                entered.await(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                workers.submit(() -> presence.unsubscribed("other", "room"))
                        .get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } finally {
                release.countDown();
            }
            assertSame(frame, pending.get(1, java.util.concurrent.TimeUnit.SECONDS));
        }
        org.mockito.Mockito.when(service.permitsMessage(1L, "first", 10L, 99L)).thenReturn(false);
        assertNull(guard.beforeHandle(frame, null, null), "queued delivery must recheck DB authority");
        org.mockito.Mockito.verify(service, org.mockito.Mockito.times(1)).permitsMessage(1L, "first", 10L, 99L);
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).socketValid(1L, 100L, "first");
    }

    @Test
    void actualHandlerUsesFreshBatchAndRechecksCoreAfterWaiting() {
        var service = org.mockito.Mockito.mock(com.muse.meomuneum.chat.participation.ChatParticipationService.class);
        var batcher = org.mockito.Mockito.mock(ChatMessagePermissionBatcher.class);
        var core = org.mockito.Mockito.mock(com.muse.meomuneum.chat.auth.ChatSessionVerifier.class);
        var presence = new ChatPresenceRegistry(Clock.systemUTC());
        presence.connect(1L, 10L, 100L, 25, "first");
        presence.subscribed("first", "room", "/topic/chat-rooms/10");
        presence.setParticipation(service);
        var registry = new ChatAuthSessionRegistry();
        registry.register("first", new ChatAuthSessionRegistry.Context(1L, java.util.UUID.randomUUID().toString()));
        var guard = new ChatMessageOutboundGuard(presence);
        guard.setParticipation(service);
        guard.setCoreVerifier(core, registry);
        guard.setPermissionBatcher(batcher);
        org.mockito.Mockito.when(service.available()).thenReturn(true);
        org.mockito.Mockito.when(batcher.permitsMessage(1L, "first", 10L, 99L)).thenReturn(true);
        var frame = MessageBuilder.fromMessage(outgoing("first", Set.of("first")))
                .setHeader("chatMessageId", 99L).build();
        assertSame(frame, guard.beforeHandle(frame, null, null));
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).permitsMessage(1L, "first", 10L, 99L);
        org.mockito.Mockito.reset(core);
        org.mockito.Mockito.doNothing().doThrow(new IllegalStateException("expired while waiting"))
                .when(core)
                .assertValid(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        assertNull(guard.beforeHandle(frame, null, null));
        org.mockito.Mockito.verify(core, org.mockito.Mockito.times(2)).assertValid(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.reset(core);
        org.mockito.Mockito.when(batcher.permitsMessage(1L, "first", 10L, 99L)).thenAnswer(call -> {
            registry.remove("first");
            return true;
        });
        assertNull(guard.beforeHandle(frame, null, null), "auth ending during the DB wait must deny delivery");
    }

    @Test
    void presenceProofIsOnlyForEnqueueAndHandlerMustUseFreshBatch() {
        var service = org.mockito.Mockito.mock(com.muse.meomuneum.chat.participation.ChatParticipationService.class);
        var batcher = org.mockito.Mockito.mock(ChatMessagePermissionBatcher.class);
        var presence = new ChatPresenceRegistry(Clock.systemUTC());
        presence.connect(1L, 10L, 100L, 25, "first");
        presence.subscribed("first", "room", "/topic/chat-rooms/10");
        presence.setParticipation(service);
        var registry = new ChatAuthSessionRegistry();
        registry.register("first", new ChatAuthSessionRegistry.Context(1L, java.util.UUID.randomUUID().toString()));
        var guard = new ChatMessageOutboundGuard(presence);
        guard.setParticipation(service);
        guard.setCoreVerifier(org.mockito.Mockito.mock(com.muse.meomuneum.chat.auth.ChatSessionVerifier.class),
                registry);
        guard.setPermissionBatcher(batcher);
        var proof = new Object();
        var frame = MessageBuilder.fromMessage(outgoing("first", Set.of("first")))
                .setHeader("chatSocketEnqueuePermissions", proof).build();
        org.mockito.Mockito.when(service.permitsSocketEnqueue(1L, "first", 10L, proof)).thenReturn(true);
        assertSame(frame, guard.preSend(frame, null));
        assertNull(guard.beforeHandle(frame, null, null));
        org.mockito.Mockito.verify(batcher).permitsSocket(1L, "first", 10L);
    }

    private Message<byte[]> outgoing(String session, Set<String> recipients) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(session);
        headers.setHeader("chatRecipients", recipients);
        headers.setHeader("chatRoomId", 10L);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
