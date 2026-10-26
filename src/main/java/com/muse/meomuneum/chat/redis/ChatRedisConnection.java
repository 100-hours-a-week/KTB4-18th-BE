package com.muse.meomuneum.chat.redis;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubListener;

/** Connection foundation only; product message routing is added in the delivery work item. */
public class ChatRedisConnection implements AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> commands;
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<ChatRedisSubscription> subscriptions = new CopyOnWriteArrayList<>();

    public ChatRedisConnection(ChatRedisProperties properties) {
        RedisURI.Builder uri = RedisURI.Builder.redis(properties.host(), properties.port())
                .withSsl(properties.ssl()).withTimeout(properties.commandTimeout());
        uri.withAuthentication(properties.username(), properties.password().toCharArray());
        client = RedisClient.create(uri.build());
        try {
            var options = ClientOptions.builder().autoReconnect(true)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .requestQueueSize(properties.requestQueueSize()).timeoutOptions(TimeoutOptions.enabled())
                    .socketOptions(SocketOptions.builder().connectTimeout(properties.connectTimeout())
                            .keepAlive(true).build());
            if (properties.ssl()) {
                var sslOptions = SslOptions.builder().handshakeTimeout(properties.connectTimeout());
                if (!properties.sslCaCertificate().isEmpty()) {
                    sslOptions.trustManager(Path.of(properties.sslCaCertificate()).toFile());
                }
                options.sslOptions(sslOptions.build());
            }
            client.setOptions(options.build());
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
        if (closed.get()) {
            throw new IllegalStateException("Chat Redis connection is closed");
        }
        return commands;
    }

    public synchronized ChatRedisSubscription openSubscription(ChatRedisChannels channels,
            RedisPubSubListener<String, String> listener) {
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(listener, "listener");
        if (closed.get()) {
            throw new IllegalStateException("Chat Redis connection is closed");
        }
        try {
            ChatRedisSubscription subscription = new ChatRedisSubscription(client.connectPubSub(), channels, listener,
                    subscriptions::remove);
            subscriptions.add(subscription);
            return subscription;
        } catch (RuntimeException failure) {
            // ACL and endpoint details must not escape through application startup errors.
            throw new IllegalStateException("Chat Redis subscription could not be initialized");
        }
    }

    public boolean areSubscriptionsReady() {
        return !closed.get() && !subscriptions.isEmpty()
                && subscriptions.stream().allMatch(ChatRedisSubscription::isReady);
    }

    public boolean isConnected() {
        return !closed.get() && connected.get() && commands.isOpen();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connected.set(false);
        try {
            for (ChatRedisSubscription subscription : subscriptions) {
                try {
                    subscription.close();
                } catch (RuntimeException ignored) {
                    // Continue closing the other resources; the client is shut down below.
                }
            }
        } finally {
            try {
                commands.close();
            } finally {
                client.shutdown(Duration.ZERO, Duration.ofSeconds(2));
            }
        }
    }
}
