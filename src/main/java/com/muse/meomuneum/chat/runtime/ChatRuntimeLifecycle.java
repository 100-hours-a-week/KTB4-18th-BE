package com.muse.meomuneum.chat.runtime;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import com.muse.meomuneum.chat.connection.ChatSocketSessions;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.redis.ChatRedisChannels;
import com.muse.meomuneum.chat.redis.ChatRedisConnection;

/** No automatic reopen: a failed instance must be replaced after dependency/recovery checks succeed. */
public final class ChatRuntimeLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ChatRuntimeLifecycle.class);
    private final JdbcTemplate jdbc;
    private final ChatParticipationService participation;
    private final ChatRedisConnection redis;
    private final ChatRedisChannels channels;
    private final ChatSocketSessions sockets;
    private final ChatRuntimeMetrics metrics;
    private final String environment;
    private final Duration drainTimeout;
    private final AtomicBoolean blocked = new AtomicBoolean();
    private final AtomicBoolean reported = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean denialReported = new AtomicBoolean();
    private volatile long permitUntil;
    private volatile boolean running;
    private volatile CheckTiming checkTiming = new CheckTiming(0, 0, 0, false);

    private record CheckTiming(long started, long completed, long duration, boolean inProgress) {
    }

    public ChatRuntimeLifecycle(JdbcTemplate jdbc, ChatParticipationService participation, ChatRedisConnection redis,
            ChatRedisChannels channels, ChatSocketSessions sockets, ChatRuntimeMetrics metrics,
            String environment, Duration drainTimeout) {
        if (drainTimeout == null || drainTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || drainTimeout.compareTo(Duration.ofSeconds(25)) > 0) {
            throw new IllegalArgumentException("Chat drain timeout must be between 1 and 25 seconds");
        }
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(1);
        this.participation = participation;
        this.redis = redis;
        this.channels = channels;
        this.sockets = sockets;
        this.metrics = metrics;
        this.environment = environment;
        this.drainTimeout = drainTimeout;
        participation.setRuntimePermit(() -> permitted(true));
        redis.onUnavailable(() -> blocked.set(true)); // Lettuce callback only latches, never DB/socket I/O.
    }

    public boolean permitted() {
        return permitted(false);
    }

    private boolean permitted(boolean diagnose) {
        boolean active = running;
        boolean failed = participation.isFailed();
        boolean latched = blocked.get();
        boolean stopping = stopped.get();
        long deadline = permitUntil;
        long now = System.nanoTime();
        boolean expired = deadline - now <= 0;
        boolean allowed = active && !failed && !latched && !stopping && !expired;
        // A metrics/readiness probe must not consume the evidence for the first participation rejection.
        if (diagnose && !allowed && denialReported.compareAndSet(false, true)) {
            try {
                CheckTiming timing = checkTiming;
                log.warn("event=chat_runtime_first_denied environment={} task={} running={} participation_failed={} "
                        + "blocked={} stopped={} permit_expired={} permit_remaining_ms={} "
                        + "last_check_start_age_ms={} last_check_complete_age_ms={} last_check_duration_ms={} "
                        + "check_in_progress={}", environment, participation.taskId(), active, failed, latched,
                        stopping, expired, TimeUnit.NANOSECONDS.toMillis(deadline - now),
                        ageMillis(now, timing.started()), ageMillis(now, timing.completed()),
                        TimeUnit.NANOSECONDS.toMillis(timing.duration()), timing.inProgress());
            } catch (RuntimeException ignored) {
                // Keep the original guard decision even if diagnostic logging fails.
            }
        }
        return allowed;
    }

    private static long ageMillis(long now, long timestamp) {
        return timestamp == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(now - timestamp);
    }

    @Override
    public void start() {
        running = true;
        check();
        if (!permitted()) {
            throw new IllegalStateException(
                    "Chat runtime is not prepared; keep routing closed and run offline recovery");
        }
    }

    @Scheduled(fixedDelayString = "${chat.runtime.check-delay-ms:500}", scheduler = "chatRuntimeCheckScheduler")
    public void check() {
        if (!running || stopped.get()) {
            return;
        }
        long started = System.nanoTime();
        CheckTiming previous = checkTiming;
        checkTiming = new CheckTiming(started, previous.completed(), previous.duration(), true);
        try {
            if (blocked.get() || !redis.isConnected() || !redis.areSubscriptionsReady()) {
                throw new IllegalStateException();
            }
            var row = jdbc.queryForMap("""
                    SELECT c.epoch,c.mode,c.redis_generation,c.redis_server_id,t.state,
                        t.lease_until>UTC_TIMESTAMP(6) AS live
                    FROM chat_runtime_control c JOIN chat_runtime_tasks t ON t.environment=c.environment
                    WHERE c.environment=? AND t.task_id=?
                    """, environment, participation.taskId());
            var expected = new ChatRuntimeRecovery.Lease(((Number) row.get("epoch")).longValue(), "unused",
                    (String) row.get("redis_generation"), (String) row.get("redis_server_id"));
            if (!"RUNNING".equals(row.get("mode")) || !"ACTIVE".equals(row.get("state"))
                    || ((Number) row.get("live")).intValue() != 1 || expected.epoch() != participation.epoch()
                    || expected.generation() == null || expected.serverId() == null
                    || !expected.serverId().equals(ChatRuntimeRecovery.serverId(redis))
                    || !expected.marker()
                            .equals(redis.commands().sync().hget(channels.runtimeMarkerKey(), "generation"))
                    || System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(1)) {
                throw new IllegalStateException();
            }
            permitUntil = started + TimeUnit.SECONDS.toNanos(1);
        } catch (RuntimeException unavailable) {
            blocked.set(true);
            permitUntil = 0;
            if (reported.compareAndSet(false, true)) {
                metrics.failed();
                log.warn("event=chat_runtime_closed exception={}", unavailable.getClass().getSimpleName());
            }
            participation.failClosed("runtime_check", unavailable, started);
        } finally {
            long completed = System.nanoTime();
            checkTiming = new CheckTiming(started, completed, completed - started, false);
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void sample() {
        if (!permitted()) {
            metrics.sample(new ChatRuntimeMetrics.Sample(Double.NaN, Double.NaN, 0));
            return;
        }
        try {
            var row = jdbc.queryForMap("""
                    SELECT COUNT(*) AS pending,COALESCE(MAX(GREATEST(0,
                        TIMESTAMPDIFF(MICROSECOND,occurred_at,UTC_TIMESTAMP(6))/1000000)),0) AS oldest
                    FROM chat_message_outbox WHERE environment=? AND epoch=? AND status='PENDING'
                    """, environment, participation.epoch());
            metrics.sample(new ChatRuntimeMetrics.Sample(((Number) row.get("pending")).doubleValue(),
                    ((Number) row.get("oldest")).doubleValue(), 1));
        } catch (RuntimeException unavailable) {
            metrics.sample(new ChatRuntimeMetrics.Sample(Double.NaN, Double.NaN, 0));
        }
    }

    @EventListener
    public void closing(ContextClosedEvent event) {
        participation.beginDrain(); // ContextClosed precedes web server shutdown; refuse ingress immediately.
        permitUntil = 0;
    }

    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public void stop(Runnable callback) {
        if (!stopped.compareAndSet(false, true)) {
            callback.run();
            return;
        }
        participation.beginDrain();
        permitUntil = 0;
        long started = System.nanoTime();
        long deadline = started + drainTimeout.toNanos();
        Thread worker = Thread.ofVirtual().name("chat-runtime-drain").start(() -> {
            try {
                while (System.nanoTime() < deadline && participation.releaseDrainingViews()) {
                    // Bounded DB chunks; other Tasks and client LEFT states are never changed.
                }
                if (System.nanoTime() < deadline) {
                    participation.finishDrain();
                }
            } catch (RuntimeException unavailable) {
                // DB/lease/mode uncertainty falls back to lease fencing and the last verified boundary.
                metrics.drainFailed();
            } finally {
                sockets.closeAll(1013, "CHAT_SERVICE_UNAVAILABLE");
            }
        });
        try {
            worker.join(drainTimeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            boolean timedOut = worker.isAlive();
            if (timedOut) {
                worker.interrupt();
            }
            metrics.drained(started, timedOut);
            log.info("event=chat_runtime_drained timed_out={} elapsed_ms={}", timedOut,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            running = false;
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
