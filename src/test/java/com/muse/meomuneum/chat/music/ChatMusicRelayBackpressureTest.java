package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.muse.meomuneum.chat.auth.ChatSessionVerifier;
import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisSubscription;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;

class ChatMusicRelayBackpressureTest {
    @Test
    void fullReceiverIsBoundedAndLatchesClosedOnceRatherThanDroppingStateSilently() throws Exception {
        var redis = mock(ChatRedisConnection.class);
        var subscription = mock(ChatRedisSubscription.class);
        var channels = mock(ChatRedisChannels.class);
        when(redis.isConnected()).thenReturn(true);
        when(subscription.isReady()).thenReturn(true);
        when(redis.openMusicSubscription(eq(channels), any())).thenReturn(subscription);
        when(channels.musicChannel(10)).thenReturn("test:chat:music:10");
        var participation = mock(ChatParticipationService.class);
        when(participation.available()).thenReturn(true);
        when(participation.epoch()).thenReturn(1L);
        var outbox = mock(ChatMusicOutboxStore.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(outbox.confirms(any())).thenAnswer(call -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return false;
        });
        var beans = new DefaultListableBeanFactory();
        try (var relay = new ChatMusicRelay(participation, mock(ChatMusicPlaybackService.class), outbox, redis,
                channels,
                mock(ChatPresenceRegistry.class), mock(ChatAuthSessionRegistry.class), mock(ChatSessionVerifier.class),
                mock(ChatViewLifecycle.class), beans.getBeanProvider(SimpMessagingTemplate.class), "test")) {
            var event = new ChatMusicRedisEvent(UUID.randomUUID().toString(), "test", "chat:" + UUID.randomUUID(),
                    1, 10, 1, 1, "QUEUE_UPDATED", Instant.now());
            relay.receive(event.channel(channels), event.encode());
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                for (int i = 0; i < 1000; i++) {
                    relay.receive(event.channel(channels), event.encode());
                }
                assertThat(relay.ready()).isFalse();
                relay.monitor();
                relay.monitor();
                verify(participation, times(1)).failClosed(eq("music_relay"), any(), anyLong());
            } finally {
                release.countDown();
            }
        }
    }
}
