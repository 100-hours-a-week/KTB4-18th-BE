package com.muse.meomuneum.chat.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent;
import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.chat.room.service.ChatRoomEntryService;
import com.muse.meomuneum.global.security.AuthSessionRevokedEvent;
import com.muse.meomuneum.global.security.SessionPair;

@Component
@ConditionalOnProperty(name = "chat.core-auth.enabled", havingValue = "true")
public final class ChatCoreAuthCoordinator {
    private final ChatCoreSessionVerifier verifier;
    private final ChatAuthSessionRegistry registry;
    private final ChatSocketSessions sockets;
    private final ChatRoomEntryService entry;
    private final CoreSessionGateway gateway;
    private final Map<SessionPair, Boolean> cleanup = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().name("chat-auth-watch-", 0).factory());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("chat-auth-poll-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor closing = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().name("chat-auth-close-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final SessionPair probe = new SessionPair(SessionPair.MAX_SAFE_INTEGER, UUID.randomUUID().toString());

    public ChatCoreAuthCoordinator(ChatCoreSessionVerifier verifier, ChatAuthSessionRegistry registry,
            ChatSocketSessions sockets, ChatRoomEntryService entry, CoreSessionGateway gateway) {
        this.verifier = verifier;
        this.registry = registry;
        this.sockets = sockets;
        this.entry = entry;
        this.gateway = gateway;
        verifier.onInactive(pair -> {
            if (cleanup.size() < 20000 || cleanup.containsKey(pair)) {
                cleanup.putIfAbsent(pair, false);
            }
        });
        timers.scheduleAtFixedRate(this::poll, 0, 2, TimeUnit.SECONDS);
        timers.scheduleAtFixedRate(this::watch, 250, 250, TimeUnit.MILLISECONDS);
    }

    @EventListener
    public void revoked(AuthSessionRevokedEvent event) {
        verifier.invalidate(event.userId(), event.sid());
    }

    private List<SessionPair> referenced() {
        return registry.snapshot().values().stream()
                .map(context -> new SessionPair(context.userId(), context.authSessionId())).distinct().toList();
    }

    private void poll() {
        try {
            List<SessionPair> connected = referenced();
            verifier.expireUnused(connected);
            var combined = new java.util.LinkedHashSet<>(connected);
            combined.addAll(verifier.recentPairs());
            List<SessionPair> pairs = List.copyOf(combined);
            if (pairs.isEmpty()) {
                submit(() -> {
                    try {
                        gateway.validate(List.of(probe));
                        verifier.serviceAvailable(true);
                    } catch (RuntimeException unavailable) {
                        verifier.serviceAvailable(false);
                    }
                });
            }
            for (int start = 0; start < pairs.size(); start += 100) {
                List<SessionPair> batch = new ArrayList<>(pairs.subList(start, Math.min(start + 100, pairs.size())));
                submit(() -> verifier.refresh(batch));
            }
        } catch (RuntimeException unavailable) {
            verifier.serviceAvailable(false);
        }
    }

    private void submit(Runnable work) {
        try {
            workers.execute(work);
        } catch (RuntimeException overloaded) {
            verifier.serviceAvailable(false);
        }
    }

    private void watch() {
        try {
            registry.snapshot().forEach((socket, context) -> {
                try {
                    if (!registry.tokenValid(socket, java.time.Instant.now())) {
                        closeLater(() -> sockets.close(java.util.Set.of(socket), 4102, "AUTH_REQUIRED"));
                        return;
                    }
                    verifier.assertValid(context.userId(), context.authSessionId());
                } catch (ChatRoomException inactive) {
                    verifier.invalidate(context.userId(), context.authSessionId());
                } catch (ChatAuthUnavailableException unavailable) {
                    // Closing work is bounded; inline guards already prevent new authorization/enqueue.
                    closeLater(() -> sockets.close(java.util.Set.of(socket), 1013, "CHAT_SERVICE_UNAVAILABLE"));
                }
            });
            cleanup.forEach((pair, queued) -> {
                if (!queued && cleanup.replace(pair, false, true)) {
                    if (!closeLater(() -> {
                        try {
                            entry.loggedOut(new ChatSessionLogoutEvent(pair.userId(), pair.sid()));
                            cleanup.remove(pair);
                        } catch (RuntimeException unavailable) {
                            cleanup.put(pair, false);
                        }
                    })) {
                        cleanup.put(pair, false);
                    }
                }
            });
        } catch (RuntimeException unavailable) {
            // A failed iteration must not cancel the watchdog permanently.
        }
    }

    private boolean closeLater(Runnable work) {
        try {
            closing.execute(work);
            return true;
        } catch (RuntimeException overloaded) {
            return false;
        }
    }

    @PreDestroy
    public void close() {
        timers.shutdownNow();
        workers.shutdownNow();
        closing.shutdownNow();
    }
}
