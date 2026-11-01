package com.muse.meomuneum.chat.message.relay;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.muse.meomuneum.chat.control.ChatPresenceEventCoalescer;
import com.muse.meomuneum.chat.message.dto.ChatMessageEvent;
import com.muse.meomuneum.chat.message.dto.ChatMessageResponse;
import com.muse.meomuneum.chat.message.service.DistributedChatMessageService;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;
import com.muse.meomuneum.chat.redis.ChatRedisSubscription;
import com.muse.meomuneum.chat.runtime.ChatRuntimeMetrics;

import io.lettuce.core.pubsub.RedisPubSubAdapter;

/** Both sender and peers use the same Pub/Sub path; ACK remains a DB-commit acknowledgement only. */
public final class ChatMessageRelay implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChatMessageRelay.class);
    private final AtomicBoolean rejectionReported = new AtomicBoolean();
    private ChatRuntimeMetrics metrics;
    private ChatDeliveryMetrics deliveryMetrics;

    @Autowired(required = false)
    public void setDeliveryMetrics(ChatDeliveryMetrics value) {
        deliveryMetrics = value;
    }

    private <T> T measured(ChatDeliveryMetrics.Stage stage, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            ChatDeliveryMetrics.record(deliveryMetrics, stage, started);
        }
    }

    @Autowired(required = false)
    public void setMetrics(ChatRuntimeMetrics metrics) {
        this.metrics = metrics;
    }
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
    private final ChatPresenceEventCoalescer presenceEvents;
    private final Object deliveryLock = new Object();
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
        presenceEvents = new ChatPresenceEventCoalescer(receivers,
                event -> processControl(event.channel(channels), event.encode()),
                failure -> receiveRejected(failure, "presence"));
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

    @Scheduled(fixedDelay = 200, scheduler = "chatRelayDispatchScheduler")
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
                            ChatMessageOutboxStore.Claim claim = measured(ChatDeliveryMetrics.Stage.OUTBOX_CLAIM,
                                    () -> outbox.claim(room));
                            if (claim == null) {
                                continue;
                            }
                            budget--;
                            progress = true;
                            if (!measured(ChatDeliveryMetrics.Stage.OUTBOX_PUBLISHABLE,
                                    () -> outbox.publishable(claim))) {
                                measured(ChatDeliveryMetrics.Stage.OUTBOX_COMPLETE,
                                        () -> outbox.complete(claim, false));
                                continue;
                            }
                            boolean sent = false;
                            RuntimeException publishFailure = null;
                            long started = System.nanoTime();
                            try {
                                // Subscriber count is not a user delivery receipt.
                                redis.commands().sync().publish(claim.event().channel(channels),
                                        claim.event().encode());
                                sent = true;
                                published.incrementAndGet();
                            } catch (RuntimeException unavailable) {
                                publishFailure = unavailable;
                                failures.incrementAndGet();
                            }
                            if (metrics != null) {
                                metrics.published(started, sent);
                            }
                            boolean completedPublish = sent;
                            measured(ChatDeliveryMetrics.Stage.OUTBOX_COMPLETE,
                                    () -> outbox.complete(claim, completedPublish));
                            if (!sent) {
                                failClosed(publishFailure);
                                return;
                            }
                        }
                    }
                } catch (RuntimeException unavailable) {
                    failures.incrementAndGet();
                    failClosed(unavailable);
                } finally {
                    publishing.set(false);
                }
            });
        } catch (RuntimeException full) {
            publishing.set(false);
            failClosed(full);
        }
    }

    private void receiveControl(String channel, String body) {
        if (!ready() || controls == null) {
            return;
        }
        ChatControlEvent event;
        try {
            event = ChatControlEvent.decode(body);
            if (!event.channel(channels).equals(channel) || event.epoch() != participation.epoch()) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            controls.rejectMalformed();
            return;
        }
        if (event.type().equals("CHAT_PRESENCE_CHANGED")) {
            presenceEvents.submit(event);
            return;
        }
        try {
            receivers.execute(() -> processControl(channel, body));
        } catch (RuntimeException full) {
            receiveRejected(full, "control");
        }
    }

    private void processControl(String channel, String body) {
        try {
            if (ready()) {
                controls.receive(channel, channels, body);
            }
        } catch (RuntimeException unavailable) {
            failures.incrementAndGet();
            failClosed(unavailable);
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
        long queued = System.nanoTime();
        try {
            receivers.execute(() -> {
                ChatDeliveryMetrics.record(deliveryMetrics, ChatDeliveryMetrics.Stage.RECEIVER_QUEUE, queued);
                try {
                    if (!ready() || !participation.available()) {
                        return;
                    }
                    var found = lookup(event);
                    if (found.isEmpty()) {
                        rejected.incrementAndGet();
                        return;
                    }
                    long waiting = System.nanoTime();
                    // Body reads may overlap. Deduplication, fresh batch permissions and enqueue stay serialized.
                    synchronized (deliveryLock) {
                        ChatDeliveryMetrics.record(deliveryMetrics, ChatDeliveryMetrics.Stage.DELIVERY_LOCK_WAIT,
                                waiting);
                        long holding = System.nanoTime();
                        try {
                            deliver(event, found.getFirst());
                        } finally {
                            ChatDeliveryMetrics.record(deliveryMetrics,
                                    ChatDeliveryMetrics.Stage.DELIVERY_LOCK_HOLD, holding);
                        }
                    }
                } catch (RuntimeException unavailable) {
                    failures.incrementAndGet();
                    failClosed(unavailable);
                }
            });
        } catch (RuntimeException full) {
            receiveRejected(full, "message");
        }
    }

    private void receiveRejected(RuntimeException failure, String kind) {
        if (failure instanceof RejectedExecutionException && rejectionReported.compareAndSet(false, true)) {
            log.warn("event=chat_relay_first_receiver_rejected kind={} active={} pool={} queued={} remaining={} "
                    + "completed={} shutdown={} terminating={}", kind, receivers.getActiveCount(),
                    receivers.getPoolSize(), receivers.getQueue().size(), receivers.getQueue().remainingCapacity(),
                    receivers.getCompletedTaskCount(), receivers.isShutdown(), receivers.isTerminating());
        }
        failClosed(failure);
    }

    private List<ChatMessageResponse> lookup(ChatMessageRedisEvent event) {
        // Registration is the dynamic Chat producer allowlist; the committed outbox binds every reference.
        return measured(ChatDeliveryMetrics.Stage.MESSAGE_LOOKUP_DB, () -> jdbc.query("""
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
                event.messageId(), LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC)));
    }

    private void deliver(ChatMessageRedisEvent event, ChatMessageResponse message) {
        if (!ready() || !participation.available() || duplicate(event, false)) {
            return;
        }
        var permissions = participation.messageEnqueuePermissions(event.roomId(), event.messageId());
        enqueue(event, message, permissions);
        duplicate(event, true);
    }

    private void enqueue(ChatMessageRedisEvent event, ChatMessageResponse message,
            ChatParticipationService.MessageEnqueuePermissions permissions) {
        Set<String> eligible = permissions.sockets();
        var candidates = measured(ChatDeliveryMetrics.Stage.LOCAL_RECIPIENT_SNAPSHOT,
                () -> presence.roomSubscriptions(event.roomId()));
        long fanoutStarted = System.nanoTime();
        Set<String> recipients = new java.util.HashSet<>();
        for (String socket : candidates.keySet()) {
            var context = auth.context(socket);
            if (eligible.contains(socket) && context.isPresent() && auth.tokenValid(socket, Instant.now())) {
                long authStarted = System.nanoTime();
                try {
                    verifier.assertValid(context.get().userId(), context.get().authSessionId());
                    recipients.add(socket);
                } catch (RuntimeException denied) {
                    // No network refresh under the local view/room serialization monitor.
                } finally {
                    ChatDeliveryMetrics.record(deliveryMetrics, ChatDeliveryMetrics.Stage.RECIPIENT_AUTH, authStarted);
                }
            }
        }
        if (deliveryMetrics != null) {
            deliveryMetrics.recipients(eligible.size(), recipients.size());
        }
        if (!recipients.isEmpty()) {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setHeader("chatRecipients", Set.copyOf(recipients));
            headers.setHeader("chatRoomId", event.roomId());
            headers.setHeader("chatMessageId", event.messageId());
            headers.setHeader("chatEnqueuePermissions", permissions);
            headers.setLeaveMutable(true);
            measured(ChatDeliveryMetrics.Stage.BROKER_ENQUEUE, () -> {
                broker.getObject().convertAndSend("/topic/chat-rooms/" + event.roomId(),
                        new ChatMessageEvent("CHAT_MESSAGE", message), headers.getMessageHeaders());
                return null;
            });
        }
        ChatDeliveryMetrics.record(deliveryMetrics, ChatDeliveryMetrics.Stage.FANOUT, fanoutStarted);
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
        failClosed(null);
    }

    private void failClosed(RuntimeException failure) {
        blocked.set(true);
        participation.failClosed("message_relay", failure, System.nanoTime());
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
        presenceEvents.close();
        subscription.close();
        publishers.shutdownNow();
        receivers.shutdownNow();
    }

    public int publisherQueueSize() {
        return publishers.getQueue().size();
    }

    public int receiverQueueSize() {
        return receivers.getQueue().size();
    }
}
