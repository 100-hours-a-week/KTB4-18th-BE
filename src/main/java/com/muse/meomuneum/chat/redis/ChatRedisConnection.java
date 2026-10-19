package com.muse.meomuneum.chat.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

/** Connection foundation only; product message routing is added in the delivery work item. */
public class ChatRedisConnection implements AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> commands;
    private final AtomicBoolean connected = new AtomicBoolean();

    public ChatRedisConnection(ChatRedisProperties properties) {
        RedisURI.Builder uri = RedisURI.Builder.redis(properties.host(), properties.port())
                .withSsl(properties.ssl()).withTimeout(properties.commandTimeout());
        if (properties.username() != null && !properties.username().isBlank()) {
            uri.withAuthentication(properties.username(), properties.password().toCharArray());
        } else if (properties.password() != null && !properties.password().isBlank()) {
            uri.withPassword(properties.password().toCharArray());
        }
        client = RedisClient.create(uri.build());
        client.setOptions(ClientOptions.builder().autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(properties.connectTimeout()).build()).build());
        try {
            commands = client.connect();
            connected.set(true);
            commands.addListener(new RedisConnectionStateListener() {
                @Override
                public void onRedisConnected(io.lettuce.core.RedisChannelHandler<?, ?> connection,
                        java.net.SocketAddress address) {
                    connected.set(true);
                }

                @Override
                public void onRedisDisconnected(io.lettuce.core.RedisChannelHandler<?, ?> connection) {
                    connected.set(false);
                }
            });
        } catch (RuntimeException failure) {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
            // Connection exceptions may contain endpoints; do not attach them to the startup error.
            throw new IllegalStateException("Chat Redis connection could not be initialized");
        }
    }

    public StatefulRedisConnection<String, String> commands() {
        return commands;
    }

    public StatefulRedisPubSubConnection<String, String> openSubscription() {
        return client.connectPubSub();
    }

    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() {
        connected.set(false);
        try {
            commands.close();
        } finally {
            client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        }
    }
}
