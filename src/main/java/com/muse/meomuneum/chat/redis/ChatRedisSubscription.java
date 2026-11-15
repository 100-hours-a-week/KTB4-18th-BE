package com.muse.meomuneum.chat.redis;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/** Tracks actual subscription acknowledgements; PING alone never proves a working subscription. */
public final class ChatRedisSubscription implements AutoCloseable {
    private final StatefulRedisPubSubConnection<String, String> connection;
    private final AtomicBoolean roomReady = new AtomicBoolean();
    private final AtomicBoolean controlReady = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<ChatRedisSubscription> onClose;

    ChatRedisSubscription(StatefulRedisPubSubConnection<String, String> connection, ChatRedisChannels channels,
            RedisPubSubListener<String, String> listener, Consumer<ChatRedisSubscription> onClose,
            Runnable onUnavailable) {
        this(connection, channels.roomPattern(), channels.controlChannel(), listener, onClose, onUnavailable);
    }

    ChatRedisSubscription(StatefulRedisPubSubConnection<String, String> connection, String expectedPattern,
            String control,
            RedisPubSubListener<String, String> listener, Consumer<ChatRedisSubscription> onClose,
            Runnable onUnavailable) {
        this.connection = connection;
        this.onClose = onClose;
        connection.addListener(new RedisConnectionStateListener() {
            @Override
            public void onRedisDisconnected(RedisChannelHandler<?, ?> disconnected) {
                roomReady.set(false);
                controlReady.set(false);
                onUnavailable.run();
            }
        });
        connection.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void subscribed(String channel, long count) {
                if (control.equals(channel)) {
                    controlReady.set(true);
                }
            }

            @Override
            public void psubscribed(String pattern, long count) {
                if (expectedPattern.equals(pattern)) {
                    roomReady.set(true);
                }
            }

            @Override
            public void unsubscribed(String channel, long count) {
                if (control.equals(channel)) {
                    controlReady.set(false);
                    onUnavailable.run();
                }
            }

            @Override
            public void punsubscribed(String pattern, long count) {
                if (expectedPattern.equals(pattern)) {
                    roomReady.set(false);
                    onUnavailable.run();
                }
            }

            @Override
            public void message(String channel, String message) {
                if (isReady() && control.equals(channel)) {
                    listener.message(channel, message);
                }
            }

            @Override
            public void message(String pattern, String channel, String message) {
                if (isReady() && expectedPattern.equals(pattern)) {
                    listener.message(pattern, channel, message);
                }
            }
        });
        try {
            connection.sync().psubscribe(expectedPattern);
            connection.sync().subscribe(control);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public boolean isReady() {
        return !closed.get() && connection.isOpen() && roomReady.get() && controlReady.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            roomReady.set(false);
            controlReady.set(false);
            try {
                connection.close();
            } finally {
                onClose.accept(this);
            }
        }
    }
}
