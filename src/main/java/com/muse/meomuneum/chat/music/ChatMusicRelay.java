package com.muse.meomuneum.chat.music;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import com.muse.meomuneum.chat.auth.ChatSessionVerifier;
import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisSubscription;
import com.muse.meomuneum.chat.view.ChatViewLifecycle;

import io.lettuce.core.pubsub.RedisPubSubAdapter;

/** At-least-once hints, including the sender, converge on fresh canonical snapshots on each Task. */
public final class ChatMusicRelay implements AutoCloseable {
    private final ChatParticipationService participation;
    private final ChatMusicPlaybackService playback;
    private final ChatMusicOutboxStore outbox;
    private final ChatRedisConnection redis;
    private final ChatRedisChannels channels;
    private final ChatRedisSubscription subscription;
    private final ChatPresenceRegistry presence;
    private final ChatAuthSessionRegistry auth;
    private final ChatSessionVerifier verifier;
    private final ChatViewLifecycle views;
    private final ObjectProvider<SimpMessagingTemplate> broker;
    private final String environment;
    private final AtomicBoolean blocked = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicBoolean publishing = new AtomicBoolean();
    private final AtomicBoolean syncing = new AtomicBoolean();
    private final ThreadPoolExecutor publishers = executor("chat-music-publish", 1, 1);
    private final ThreadPoolExecutor receivers = executor("chat-music-receive", 1, 128);
    // Only the single receiver mutates this bounded cache. Separate type keys preserve equal-version event pairs.
    private final LinkedHashMap<String, Watermark> seen = new LinkedHashMap<>(128, 0.75f, true);
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final LinkedHashMap<Long, Long> syncTimes = new LinkedHashMap<>();

    public ChatMusicRelay(ChatParticipationService participation, ChatMusicPlaybackService playback,
            ChatMusicOutboxStore outbox, ChatRedisConnection redis, ChatRedisChannels channels,
            ChatPresenceRegistry presence, ChatAuthSessionRegistry auth, ChatSessionVerifier verifier,
            ChatViewLifecycle views, ObjectProvider<SimpMessagingTemplate> broker, String environment) {
        this.participation = participation;
        this.playback = playback;
        this.outbox = outbox;
        this.redis = redis;
        this.channels = channels;
        this.presence = presence;
        this.auth = auth;
        this.verifier = verifier;
        this.views = views;
        this.broker = broker;
        this.environment = environment;
        participation.setMusicTransportReady(this::ready);
        subscription = redis.openMusicSubscription(channels, new RedisPubSubAdapter<>() {
            @Override
            public void message(String pattern, String channel, String body) {
                receive(channel, body);
            }
        });
        redis.onUnavailable(() -> blocked.set(true));
    }

    public boolean ready() {
        return !blocked.get() && redis.isConnected() && subscription != null && subscription.isReady();
    }

    public long publishedEvents() {
        return published.get();
    }

    public long rejectedEvents() {
        return rejected.get();
    }

    public long failures() {
        return failures.get();
    }

    @Scheduled(fixedDelay = 50)
    public void monitor() {
        if (!ready()) {
            failClosed();
        }
    }

    @Scheduled(fixedDelay = 200)
    public void dispatch() {
        publishWork(() -> {
            for (long room : outbox.candidateRooms()) {
                if (!ready() || !participation.available()) {
                    return;
                }
                var claim = outbox.claim(room);
                if (claim == null) {
                    continue;
                }
                if (!outbox.publishable(claim)) {
                    continue;
                }
                boolean sent = false;
                try {
                    // No DB transaction is open here. PUBLISH subscriber count is not a user receipt.
                    redis.commands().sync().publish(claim.event().channel(channels), claim.event().encode());
                    sent = true;
                    published.incrementAndGet();
                } finally {
                    outbox.complete(claim, sent);
                }
            }
        });
    }

    public void receive(String channel, String body) {
        ChatMusicRedisEvent event;
        try {
            event = ChatMusicRedisEvent.decode(channel, channels, environment, body);
            if (event.epoch() != participation.epoch()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            rejected.incrementAndGet();
            return;
        }
        receiveWork(() -> {
            if (!outbox.confirms(event)) {
                rejected.incrementAndGet();
                return;
            }
            if (presence.roomSubscriptions(event.roomId()).isEmpty()) {
                return;
            }
            var snapshot = read(event.roomId());
            long version = ((Number) snapshot.get("version")).longValue();
            if (version < event.musicVersion()) {
                rejected.incrementAndGet();
                return;
            }
            long now = System.nanoTime();
            seen.values().removeIf(mark -> mark.until() - now <= 0);
            String key = event.epoch() + ":" + event.roomId() + ":" + event.type();
            var mark = seen.get(key);
            if (mark != null && mark.version() >= version) {
                return;
            }
            enqueue(event.roomId(), event.type(), snapshot, event);
            seen.put(key, new Watermark(version, now + TimeUnit.MINUTES.toNanos(10)));
            while (seen.size() > 2048) {
                seen.remove(seen.keySet().iterator().next());
            }
        });
    }

    @Scheduled(fixedDelay = 250)
    public void sync() {
        if (!ready() || !participation.available() || !syncing.compareAndSet(false, true)) {
            return;
        }
        receiveWork(() -> {
            try {
                long now = System.nanoTime();
                var localRooms = views.localViews().values().stream().map(ChatViewLifecycle.LocalView::roomId)
                        .collect(java.util.stream.Collectors.toSet());
                syncTimes.keySet().removeIf(room -> !localRooms.contains(room));
                var rooms = localRooms.stream().sorted()
                        .filter(room -> now - syncTimes.getOrDefault(room,
                                now - TimeUnit.SECONDS.toNanos(5)) >= TimeUnit.SECONDS.toNanos(5))
                        .limit(25).toList();
                for (long room : rooms) {
                    if (!ready() || !participation.available()) {
                        return;
                    }
                    if (!presence.roomSubscriptions(room).isEmpty()) {
                        enqueue(room, "PLAYBACK_SYNC", read(room), null);
                        syncTimes.put(room, System.nanoTime());
                    }
                    while (syncTimes.size() > 1024) {
                        syncTimes.remove(syncTimes.keySet().iterator().next());
                    }
                }
            } finally {
                syncing.set(false);
            }
        });
    }

    private Map<String, Object> read(long room) {
        return participation.systemMusicTransaction(room, tx -> playback.snapshot(room));
    }

    private void enqueue(long room, String type, Map<String, Object> snapshot, ChatMusicRedisEvent event) {
        if (!ready() || !participation.available()) {
            return;
        }
        var permissions = participation.issueMusicEnqueuePermissions(room);
        Set<String> recipients = new HashSet<>();
        for (String socket : presence.roomSubscriptions(room).keySet()) {
            var context = auth.context(socket);
            if (context.isEmpty() || !auth.tokenValid(socket, Instant.now())
                    || !participation.permitsSocketEnqueue(context.get().userId(), socket, room, permissions)) {
                continue;
            }
            try {
                verifier.assertValid(context.get().userId(), context.get().authSessionId());
                recipients.add(socket);
            } catch (RuntimeException denied) {
                // Current per-account Core denial never grants delivery through a room subscription.
            }
        }
        if (recipients.isEmpty() || !ready() || !participation.available()) {
            return;
        }
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setHeader("chatRecipients", Set.copyOf(recipients));
        headers.setHeader("chatRoomId", room);
        headers.setHeader("chatMusicVersion", snapshot.get("version"));
        headers.setHeader("chatSocketEnqueuePermissions", permissions);
        headers.setLeaveMutable(true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("room_id", room);
        data.put("epoch", participation.epoch());
        data.put("version", snapshot.get("version"));
        data.put("event_id", event == null ? null : event.eventId());
        data.put("event_version", event == null ? null : event.musicVersion());
        data.put("queue_refresh_required", true);
        data.put("playback", snapshot);
        broker.getObject().convertAndSend("/topic/chat-rooms/" + room,
                Map.of("type", type, "data", data), headers.getMessageHeaders());
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        publishWork(outbox::cleanup);
    }

    private void publishWork(Runnable operation) {
        if (!ready() || !participation.available() || !publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            publishers.execute(() -> {
                try {
                    if (ready() && participation.available()) {
                        operation.run();
                    }
                } catch (RuntimeException unavailable) {
                    failClosed();
                } finally {
                    publishing.set(false);
                }
            });
        } catch (RuntimeException full) {
            publishing.set(false);
            failClosed();
        }
    }

    private void receiveWork(Runnable operation) {
        if (!ready() || !participation.available()) {
            return;
        }
        try {
            receivers.execute(() -> {
                try {
                    if (ready() && participation.available()) {
                        operation.run();
                    }
                } catch (RuntimeException unavailable) {
                    failClosed();
                }
            });
        } catch (RuntimeException full) {
            failClosed();
        }
    }

    private void failClosed() {
        blocked.set(true);
        if (failureReported.compareAndSet(false, true)) {
            failures.incrementAndGet();
            participation.failClosed("music_relay", null, System.nanoTime());
        }
    }

    private static ThreadPoolExecutor executor(String name, int count, int size) {
        return new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(size), task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void close() {
        blocked.set(true);
        subscription.close();
        publishers.shutdownNow();
        receivers.shutdownNow();
    }

    private record Watermark(long version, long until) {
    }
}
