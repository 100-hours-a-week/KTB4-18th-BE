package com.muse.meomuneum.chat.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;

class ChatRedisSubscriptionTest {
    @Test
    @SuppressWarnings("unchecked")
    void onlyExpectedPatternCanChangeReadinessOrForwardMessages() {
        StatefulRedisPubSubConnection<String, String> connection = mock(StatefulRedisPubSubConnection.class);
        RedisPubSubCommands<String, String> commands = mock(RedisPubSubCommands.class);
        RedisPubSubListener<String, String> downstream = mock(RedisPubSubListener.class);
        var callbacks = new AtomicReference<RedisPubSubListener<String, String>>();
        var unavailable = mock(Runnable.class);
        when(connection.sync()).thenReturn(commands);
        when(connection.isOpen()).thenReturn(true);
        doAnswer(call -> {
            callbacks.set(call.getArgument(0));
            return null;
        }).when(connection).addListener(any(RedisPubSubListener.class));

        String expected = "test:chat:music:*";
        String other = "test:chat:room:*";
        try (var subscription = new ChatRedisSubscription(connection, expected, "test:control", downstream,
                ignored -> {
                }, unavailable)) {
            var callback = callbacks.get();
            verify(commands).psubscribe(expected);
            callback.subscribed("test:control", 1);
            callback.psubscribed(other, 2);
            assertThat(subscription.isReady()).isFalse();
            callback.message(expected, "test:chat:music:10", "before-ready");
            verify(downstream, never()).message(expected, "test:chat:music:10", "before-ready");

            callback.psubscribed(expected, 2);
            assertThat(subscription.isReady()).isTrue();
            callback.message(other, "test:chat:room:10", "unexpected");
            verify(downstream, never()).message(other, "test:chat:room:10", "unexpected");
            callback.message(expected, "test:chat:music:10", "expected");
            verify(downstream).message(expected, "test:chat:music:10", "expected");

            callback.punsubscribed(other, 1);
            assertThat(subscription.isReady()).isTrue();
            verify(unavailable, never()).run();
            callback.punsubscribed(expected, 1);
            assertThat(subscription.isReady()).isFalse();
            verify(unavailable).run();
        }
    }
}
