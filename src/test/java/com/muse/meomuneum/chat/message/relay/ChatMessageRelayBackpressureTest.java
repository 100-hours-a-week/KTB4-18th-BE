package com.muse.meomuneum.chat.message.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.muse.meomuneum.chat.auth.ChatSessionVerifier;
import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.control.ChatControlEvent;
import com.muse.meomuneum.chat.control.ChatControlEventConsumer;
import com.muse.meomuneum.chat.message.service.DistributedChatMessageService;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisSubscription;

class ChatMessageRelayBackpressureTest {
    @Test
    void twoBlockedReceiversDoNotAccumulateSameRoomPresenceBursts() throws Exception {
        var redis = mock(ChatRedisConnection.class);
        var subscription = mock(ChatRedisSubscription.class);
        var channels = mock(ChatRedisChannels.class);
        when(redis.isConnected()).thenReturn(true);
        when(subscription.isReady()).thenReturn(true);
        when(redis.openSubscription(eq(channels), any())).thenReturn(subscription);
        when(channels.roomChannel(anyLong())).thenAnswer(call -> "test:room:" + call.getArgument(0));
        var participation = mock(ChatParticipationService.class);
        when(participation.epoch()).thenReturn(1L);
        var controls = mock(ChatControlEventConsumer.class);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var latest = new CountDownLatch(2);
        doAnswer(call -> {
            var event = ChatControlEvent.decode(call.getArgument(2));
            if (event.roomSeq() == 1) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            if (event.roomSeq() == 1001) {
                latest.countDown();
            }
            return null;
        }).when(controls).receive(anyString(), eq(channels), anyString());
        var beans = new DefaultListableBeanFactory();
        try (var relay = new ChatMessageRelay(mock(JdbcTemplate.class), participation,
                mock(ChatMessageOutboxStore.class), mock(DistributedChatMessageService.class), redis, channels,
                mock(ChatPresenceRegistry.class), mock(ChatAuthSessionRegistry.class), mock(ChatSessionVerifier.class),
                beans.getBeanProvider(SimpMessagingTemplate.class), "test")) {
            relay.setControls(controls);
            try {
                for (long room = 1; room <= 2; room++) {
                    relay.receive("test:room:" + room, presence(room, 1).encode());
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                for (long seq = 2; seq <= 1001; seq++) {
                    for (long room = 1; room <= 2; room++) {
                        relay.receive("test:room:" + room, presence(room, seq).encode());
                    }
                }
                assertTrue(relay.ready(), "a presence burst must not fail a live transport");
                assertEquals(0, relay.receiverQueueSize(), "pending snapshots must not occupy executor queue slots");
            } finally {
                release.countDown();
            }
            assertTrue(latest.await(3, TimeUnit.SECONDS));
            assertTrue(relay.ready());
        }
    }

    @Test
    void presenceBurstKeepsOnlyLatestHintAndDoesNotBlockTerminalEvents() throws Exception {
        var redis = mock(ChatRedisConnection.class);
        var subscription = mock(ChatRedisSubscription.class);
        var channels = mock(ChatRedisChannels.class);
        when(redis.isConnected()).thenReturn(true);
        when(subscription.isReady()).thenReturn(true);
        when(redis.openSubscription(eq(channels), any())).thenReturn(subscription);
        when(channels.roomChannel(1)).thenReturn("test:room:1");
        when(channels.controlChannel()).thenReturn("test:control");
        var participation = mock(ChatParticipationService.class);
        when(participation.epoch()).thenReturn(1L);
        var controls = mock(ChatControlEventConsumer.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var terminal = new CountDownLatch(1);
        var latest = new CountDownLatch(1);
        var sequences = new ConcurrentLinkedQueue<Long>();
        doAnswer(call -> {
            var event = ChatControlEvent.decode(call.getArgument(2));
            if (!event.type().equals("CHAT_PRESENCE_CHANGED")) {
                terminal.countDown();
            } else {
                sequences.add(event.roomSeq());
                if (event.roomSeq() == 1) {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                }
                if (event.roomSeq() == 1001) {
                    latest.countDown();
                }
            }
            return null;
        }).when(controls).receive(anyString(), eq(channels), anyString());
        var beans = new DefaultListableBeanFactory();
        try (var relay = new ChatMessageRelay(mock(JdbcTemplate.class), participation,
                mock(ChatMessageOutboxStore.class), mock(DistributedChatMessageService.class), redis, channels,
                mock(ChatPresenceRegistry.class), mock(ChatAuthSessionRegistry.class), mock(ChatSessionVerifier.class),
                beans.getBeanProvider(SimpMessagingTemplate.class), "test")) {
            relay.setControls(controls);
            try {
                relay.receive("test:room:1", presence(1).encode());
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                for (long seq = 2; seq <= 1001; seq++) {
                    relay.receive("test:room:1", presence(seq).encode());
                }
                assertTrue(relay.ready(), "same-room snapshot hints must not fill the receiver queue");
                relay.receive("test:wrong", presence(9000).encode());
                var stale = presence(9001);
                relay.receive("test:room:1", new ChatControlEvent(stale.eventId(), stale.type(), stale.producerId(),
                        stale.occurredAt(), 2, stale.payload()).encode());
                relay.receive("test:control", new ChatControlEvent(UUID.randomUUID().toString(),
                        "CHAT_MEMBERSHIP_ENDED", "chat:" + UUID.randomUUID(), Instant.now(), 1,
                        Map.of("room_id", 1L, "room_seq", 1002L, "user_id", 1L, "membership_id", 1L,
                                "sid", UUID.randomUUID().toString(), "reason", "SESSION_REVOKED"))
                        .encode());
                assertTrue(terminal.await(3, TimeUnit.SECONDS), "terminal events must not wait for presence fan-out");
            } finally {
                release.countDown();
            }
            assertTrue(latest.await(3, TimeUnit.SECONDS));
            assertEquals(java.util.List.of(1L, 1001L), java.util.List.copyOf(sequences));
            assertTrue(relay.ready());
        }
    }

    @Test
    void blockedDeliveryDbReadDoesNotHoldTheLocalPresenceMonitor() throws Exception {
        var redis = mock(ChatRedisConnection.class);
        var subscription = mock(ChatRedisSubscription.class);
        var channels = mock(ChatRedisChannels.class);
        when(redis.isConnected()).thenReturn(true);
        when(subscription.isReady()).thenReturn(true);
        when(redis.openSubscription(eq(channels), any())).thenReturn(subscription);
        when(channels.roomPattern()).thenReturn("test:room:*");
        var participation = mock(ChatParticipationService.class);
        when(participation.epoch()).thenReturn(1L);
        when(participation.available()).thenReturn(true);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var jdbc = mock(JdbcTemplate.class, call -> {
            if (call.getMethod().getName().equals("query")) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return java.util.List.of();
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        var presence = new ChatPresenceRegistry(java.time.Clock.systemUTC());
        var beans = new DefaultListableBeanFactory();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                var relay = new ChatMessageRelay(jdbc, participation, mock(ChatMessageOutboxStore.class),
                        mock(DistributedChatMessageService.class), redis, channels, presence,
                        mock(ChatAuthSessionRegistry.class), mock(ChatSessionVerifier.class),
                        beans.getBeanProvider(SimpMessagingTemplate.class), "test")) {
            try {
                var event = new ChatMessageRedisEvent(UUID.randomUUID().toString(), "chat:" + UUID.randomUUID(),
                        1, 1, 1, 1, Instant.now());
                relay.receive("test:room:1", event.encode());
                var other = new ChatMessageRedisEvent(UUID.randomUUID().toString(), "chat:" + UUID.randomUUID(),
                        1, 1, 2, 2, Instant.now());
                relay.receive("test:room:1", other.encode());
                assertTrue(entered.await(3, TimeUnit.SECONDS),
                        "both body reads must enter DB without waiting for deliveryLock");
                assertTrue(executor.submit(() -> presence.exclusive(() -> true)).get(500, TimeUnit.MILLISECONDS),
                        "a delivery DB read must not block local transitions and scheduled reconciliation");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void concurrentDuplicateBodyReadsStillPublishOnlyOneBoundEnqueue() throws Exception {
        var redis = mock(ChatRedisConnection.class);
        var subscription = mock(ChatRedisSubscription.class);
        var channels = mock(ChatRedisChannels.class);
        when(redis.isConnected()).thenReturn(true);
        when(subscription.isReady()).thenReturn(true);
        when(redis.openSubscription(eq(channels), any())).thenReturn(subscription);
        when(channels.roomPattern()).thenReturn("test:room:*");
        var participation = mock(ChatParticipationService.class);
        when(participation.epoch()).thenReturn(1L);
        when(participation.available()).thenReturn(true);
        var permissions = mock(ChatParticipationService.MessageEnqueuePermissions.class);
        when(permissions.sockets()).thenReturn(java.util.Set.of("recipient"));
        when(participation.messageEnqueuePermissions(1, 1)).thenReturn(permissions);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var response = new com.muse.meomuneum.chat.message.dto.ChatMessageResponse(1L, UUID.randomUUID().toString(),
                1L, 1L, "sender", "content", java.time.OffsetDateTime.now());
        var jdbc = mock(JdbcTemplate.class, call -> {
            if (call.getMethod().getName().equals("query")) {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return java.util.List.of(response);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        var presence = mock(ChatPresenceRegistry.class);
        when(presence.roomSubscriptions(1L)).thenReturn(Map.of("recipient", java.util.Set.of("sub")));
        var auth = mock(ChatAuthSessionRegistry.class);
        when(auth.context("recipient")).thenReturn(java.util.Optional.of(
                new ChatAuthSessionRegistry.Context(1L, UUID.randomUUID().toString())));
        when(auth.tokenValid(eq("recipient"), any())).thenReturn(true);
        var template = mock(SimpMessagingTemplate.class);
        doAnswer(call -> {
            Map<?, ?> headers = call.getArgument(2);
            org.junit.jupiter.api.Assertions.assertSame(permissions, headers.get("chatEnqueuePermissions"));
            return null;
        }).when(template).convertAndSend(eq("/topic/chat-rooms/1"), any(Object.class), any(Map.class));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("broker", template);
        try (var relay = new ChatMessageRelay(jdbc, participation, mock(ChatMessageOutboxStore.class),
                mock(DistributedChatMessageService.class), redis, channels, presence, auth,
                mock(ChatSessionVerifier.class), beans.getBeanProvider(SimpMessagingTemplate.class), "test")) {
            var event = new ChatMessageRedisEvent(UUID.randomUUID().toString(), "chat:" + UUID.randomUUID(),
                    1, 1, 1, 1, Instant.now());
            try {
                relay.receive("test:room:1", event.encode());
                relay.receive("test:room:1", event.encode());
                assertTrue(entered.await(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            var workers = (java.util.concurrent.ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils
                    .getField(relay, "receivers");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (workers.getCompletedTaskCount() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(2, workers.getCompletedTaskCount());
            org.mockito.Mockito.verify(template, org.mockito.Mockito.times(1))
                    .convertAndSend(eq("/topic/chat-rooms/1"), any(Object.class), any(Map.class));
            assertTrue(relay.ready());
        }
    }

    private ChatControlEvent presence(long sequence) {
        return presence(1, sequence);
    }

    private ChatControlEvent presence(long room, long sequence) {
        return new ChatControlEvent(UUID.randomUUID().toString(), "CHAT_PRESENCE_CHANGED",
                "chat:" + UUID.randomUUID(), Instant.now(), 1, Map.of("room_id", room, "room_seq", sequence));
    }
}
