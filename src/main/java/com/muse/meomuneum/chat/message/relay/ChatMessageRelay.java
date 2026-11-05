package com.muse.meomuneum.chat.message.relay;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import com.muse.meomuneum.chat.auth.ChatSessionVerifier;
import com.muse.meomuneum.chat.connection.ChatAuthSessionRegistry;
import com.muse.meomuneum.chat.connection.ChatPresenceRegistry;
import com.muse.meomuneum.chat.control.ChatControlEvent;
import com.muse.meomuneum.chat.control.ChatControlEventConsumer;
import com.muse.meomuneum.chat.message.dto.ChatMessageEvent;
import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.chat.message.service.DistributedChatMessageService;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisSubscription;

import io.lettuce.core.pubsub.RedisPubSubAdapter;

/** Both sender and peers use the same Pub/Sub path; ACK remains a DB-commit acknowledgement only. */
public final class ChatMessageRelay implements AutoCloseable {
    private ChatControlEventConsumer controls;

    @Autowired(required = false)
    public void setControls(ChatControlEventConsumer controls) {
        this.controls = controls;
    }

    private final ChatParticipationService participation;
    private final ChatMessageOutboxStore outbox;
    private final DistributedChatMessageService messages;
    private final ChatRedisConnection redis;
    private final ChatRedisChannels channels;
    private final ChatRedisSubscription subscription;
    private final ChatPresenceRegistry presence;
    private final ChatAuthSessionRegistry auth;
    private final ChatSessionVerifier verifier;
    private final ObjectProvider<SimpMessagingTemplate> broker;
    private final JdbcTemplate jdbc;
    private final String environment;
    private final AtomicBoolean blocked = new AtomicBoolean();
    private final AtomicBoolean publishing = new AtomicBoolean();
    private final ThreadPoolExecutor publishers = executor("chat-message-publish", 2, 100);
    private final ThreadPoolExecutor receivers = executor("chat-message-receive", 2, 256);
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>(128, 0.75f, true);
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public ChatMessageRelay(JdbcTemplate jdbc, ChatParticipationService participation, ChatMessageOutboxStore outbox,
            DistributedChatMessageService messages, ChatRedisConnection redis, ChatRedisChannels channels,
            ChatPresenceRegistry presence, ChatAuthSessionRegistry auth, ChatSessionVerifier verifier,
            ObjectProvider<SimpMessagingTemplate> broker, String environment) {
        this.jdbc = jdbc;
        this.participation = participation;
        this.outbox = outbox;
        this.messages = messages;
        this.redis = redis;
        this.channels = channels;
        this.presence = presence;
        this.auth = auth;
        this.verifier = verifier;
        this.broker = broker;
        this.environment = environment;
        subscription = redis.openSubscription(channels, new RedisPubSubAdapter<>() {
            @Override
            public void message(String pattern, String channel, String body) {
                receive(channel, body);
            }

            @Override
            public void message(String channel, String body) {
                if (controls != null && !ChatControlEvent.isCoreAuth(body)) {
                    receiveControl(channel, body);
                }
            }
        });
        redis.onUnavailable(() -> blocked.set(true));
        participation.setMessageTransportReady(this::ready);
    }

    public boolean ready() {
        return !blocked.get() && redis.isConnected() && subscription != null && subscription.isReady();
    }

    public long rejectedEvents() {
        return rejected.get();
    }

    public long publishedEvents() {
        return published.get();
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
        if (!ready() || !participation.available() || !publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            // At most one bounded round is outstanding; claiming starts on the worker, not in the queue.
            publishers.execute(() -> {
                try {
                    var rooms = outbox.candidateRooms();
                    int budget = 100;
                    boolean progress = true;
                    while (budget > 0 && progress && ready() && participation.available()) {
                        progress = false;
                        for (long room : rooms) {
                            if (!ready() || !participation.available() || budget == 0) {
                                break;
                            }
                            ChatMessageOutboxStore.Claim claim = outbox.claim(room);
                            if (claim == null) {
                                continue;
                            }
                            budget--;
                            progress = true;
                            if (!outbox.publishable(claim)) {
                                outbox.complete(claim, false);
                                continue;
                            }
                            boolean sent = false;
                            try {
                                // Subscriber count is not a user delivery receipt.
                                redis.commands().sync().publish(claim.event().channel(channels),
                                        claim.event().encode());
                                sent = true;
                                published.incrementAndGet();
                            } catch (RuntimeException unavailable) {
                                failures.incrementAndGet();
                            }
                            outbox.complete(claim, sent);
                            if (!sent) {
                                failClosed();
                                return;
                            }
                        }
                    }
                } catch (RuntimeException unavailable) {
                    failures.incrementAndGet();
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

    private void receiveControl(String channel, String body) {
        if (!ready() || controls == null) {
            return;
        }
        try {
            ChatControlEvent.decode(body); // Bound and validate before accepting bytes into the finite queue.
        } catch (RuntimeException invalid) {
            controls.rejectMalformed();
            return;
        }
        try {
            receivers.execute(() -> {
                try {
                    if (ready()) {
                        controls.receive(channel, channels, body);
                    }
                } catch (RuntimeException unavailable) {
                    failures.incrementAndGet();
                    failClosed();
                }
            });
        } catch (RuntimeException full) {
            failClosed();
        }
    }

    public void receive(String channel, String body) {
        if (controls != null && ChatControlEvent.isControl(body)) {
            receiveControl(channel, body);
            return;
        }
        ChatMessageRedisEvent event;
        try {
            event = ChatMessageRedisEvent.decode(channel, channels.roomPattern().replace("*", ""), body);
        } catch (RuntimeException invalid) {
            rejected.incrementAndGet();
            return;
        }
        if (!ready() || event.epoch() != participation.epoch()) {
            rejected.incrementAndGet();
            return;
        }
        try {
            receivers.execute(() -> {
                try {
                    presence.exclusive(() -> {
                        deliver(event);
                        return null;
                    });
                } catch (RuntimeException unavailable) {
                    failures.incrementAndGet();
                    failClosed();
                }
            });
        } catch (RuntimeException full) {
            failClosed(); // A dropped queue item is a service interruption, not successful delivery.
        }
    }

    private void deliver(ChatMessageRedisEvent event) {
        if (!ready() || !participation.available() || duplicate(event, false)) {
            return;
        }
        // Registration is the dynamic Chat producer allowlist; the committed outbox binds every reference.
        var found = jdbc.query("""
                SELECT m.*,u.nickname FROM chat_message_outbox x
                JOIN chat_runtime_tasks t ON x.producer_id=CONCAT('chat:',t.task_id)
                    AND t.environment=x.environment AND t.epoch=x.epoch
                JOIN chat_message_order o ON o.environment=x.environment AND o.message_id=x.message_id
                    AND o.room_id=x.room_id AND o.room_seq=x.room_seq AND o.epoch=x.epoch
                JOIN chat_room_messages m ON m.id=x.message_id JOIN users u ON u.id=m.user_id
                WHERE x.event_id=? AND x.environment=? AND x.epoch=? AND x.producer_id=?
                    AND x.room_id=? AND x.room_seq=? AND x.message_id=? AND x.occurred_at=?
                    AND x.expires_at>UTC_TIMESTAMP(6) AND m.created_at>TIMESTAMPADD(HOUR,-24,UTC_TIMESTAMP(6))
                """, (r, i) -> new ChatMessageResponse(r.getLong("id"), r.getString("client_message_id"),
                r.getLong("room_id"), r.getLong("user_id"), r.getString("nickname"), r.getString("content"),
                r.getObject("created_at", LocalDateTime.class).atOffset(ZoneOffset.UTC)),
                event.eventId(), environment, event.epoch(), event.producerId(), event.roomId(), event.roomSeq(),
                event.messageId(), LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
        if (found.isEmpty()) {
            rejected.incrementAndGet();
            return;
        }
        Set<String> eligible = participation.messageRecipients(event.roomId(), event.messageId());
        var candidates = presence.roomSubscriptions(event.roomId());
        Set<String> recipients = new java.util.HashSet<>();
        for (String socket : candidates.keySet()) {
            var context = auth.context(socket);
            if (eligible.contains(socket) && context.isPresent() && auth.tokenValid(socket, Instant.now())) {
                try {
                    verifier.assertValid(context.get().userId(), context.get().authSessionId());
                    recipients.add(socket);
                } catch (RuntimeException denied) {
                    // No network refresh under the local view/room serialization monitor.
                }
            }
        }
        if (!recipients.isEmpty()) {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setHeader("chatRecipients", Set.copyOf(recipients));
            headers.setHeader("chatRoomId", event.roomId());
            headers.setHeader("chatMessageId", event.messageId());
            headers.setLeaveMutable(true);
            broker.getObject().convertAndSend("/topic/chat-rooms/" + event.roomId(),
                    new ChatMessageEvent("CHAT_MESSAGE", found.getFirst()), headers.getMessageHeaders());
        }
        duplicate(event, true);
    }

    private boolean duplicate(ChatMessageRedisEvent event, boolean record) {
        long now = System.nanoTime();
        seen.values().removeIf(deadline -> deadline - now <= 0);
        String key = event.epoch() + ":" + event.messageId();
        if (seen.get(key) != null || seen.get(event.eventId()) != null) {
            return true;
        }
        if (record) {
            seen.put(key, now + TimeUnit.MINUTES.toNanos(10));
            seen.put(event.eventId(), now + TimeUnit.MINUTES.toNanos(10));
            while (seen.size() > 10000) {
                seen.remove(seen.keySet().iterator().next());
            }
        }
        return false;
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        if (participation.available()) {
            try {
                outbox.cleanup();
                messages.cleanup();
            } catch (RuntimeException unavailable) {
                failures.incrementAndGet();
            }
        }
    }

    private void failClosed() {
        blocked.set(true);
        participation.failClosed();
    }

    private static ThreadPoolExecutor executor(String name, int workers, int queue) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue),
                task -> {
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
}
