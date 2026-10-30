package com.muse.meomuneum.auth.internal;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.context.event.EventListener;

import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.global.security.AuthRevocationProtocol;
import com.muse.meomuneum.global.security.AuthSessionRevokedEvent;

/** Best effort fast path. A publish failure must never undo a committed Core revocation. */
public final class CoreAuthRevocationPublisher implements AutoCloseable {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1024), Thread.ofPlatform().name("core-auth-publish-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong failed = new AtomicLong();
    private volatile ChatRedisConnection connection;
    private final Supplier<ChatRedisConnection> connections;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String channel;
    private final String producer;

    public CoreAuthRevocationPublisher(Supplier<ChatRedisConnection> connections, String channel, String producer) {
        if (!AuthRevocationProtocol.validProducer(producer)) {
            throw new IllegalArgumentException("Core authentication producer identifier required");
        }
        this.connections = connections;
        this.channel = channel;
        this.producer = producer;
    }

    @EventListener
    public void revoked(AuthSessionRevokedEvent event) {
        try {
            worker.execute(() -> {
                try {
                    if (closed.get()) {
                        return;
                    }
                    if (connection == null) {
                        var created = connections.get();
                        connection = created;
                        if (closed.get()) {
                            created.close();
                            return;
                        }
                    }
                    connection.commands().sync().publish(channel, AuthRevocationProtocol.encode(event, producer));
                } catch (RuntimeException unavailable) {
                    failed.incrementAndGet();
                }
            });
        } catch (RuntimeException overloaded) {
            failed.incrementAndGet();
        }
    }

    public long failedPublications() {
        return failed.get();
    }

    @Override
    public void close() {
        closed.set(true);
        worker.shutdownNow();
        var current = connection;
        if (current != null) {
            current.close();
        }
    }
}
