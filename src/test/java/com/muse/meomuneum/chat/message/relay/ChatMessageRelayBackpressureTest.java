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

    private ChatControlEvent presence(long sequence) {
        return presence(1, sequence);
    }

    private ChatControlEvent presence(long room, long sequence) {
        return new ChatControlEvent(UUID.randomUUID().toString(), "CHAT_PRESENCE_CHANGED",
                "chat:" + UUID.randomUUID(), Instant.now(), 1, Map.of("room_id", room, "room_seq", sequence));
    }
}
