package com.muse.meomuneum.auth.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.LoggerFactory;

import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;
import com.muse.meomuneum.user.repository.UserRepository;

/** Read only: never refreshes, rotates, revokes or extends a session. */
public final class CoreSessionValidationService implements AutoCloseable {
    private final HazelcastAuthStateStore sessions;
    private final UserRepository users;
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("core-chat-validation-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public CoreSessionValidationService(HazelcastAuthStateStore sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    public List<SessionDecision> validate(List<SessionPair> pairs) {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(1);
        var progress = new ValidationProgress();
        var future = workers.submit(() -> {
            progress.workerStarted = System.nanoTime();
            progress.stage = "users";
            var activeUsers = users.findActiveIds(pairs.stream().map(SessionPair::userId).distinct().toList());
            progress.usersNanos = System.nanoTime() - progress.workerStarted;
            progress.stage = "sessions";
            Instant readStarted = Instant.now();
            progress.sessionStarted = System.nanoTime();
            var currentSessions = sessions.getSessions(pairs.stream().map(SessionPair::sid)
                    .collect(java.util.stream.Collectors.toSet()));
            progress.sessionStarted = 0;
            List<SessionDecision> results = new ArrayList<>();
            for (SessionPair pair : pairs) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("validation unavailable");
                }
                var session = currentSessions.get(pair.sid());
                progress.sessionsCompleted++;
                boolean active = session != null && pair.sid().equals(session.sid())
                        && pair.userId() == session.userId()
                        && session.status() == HazelcastAuthStateStore.SessionStatus.ACTIVE
                        && activeUsers.contains(pair.userId());
                long remaining = active
                        ? Math.min(Duration.between(readStarted, session.refreshIdleExpiresAt()).toMillis(),
                                Duration.between(readStarted, session.refreshAbsoluteExpiresAt()).toMillis())
                        : 0;
                if (active && (session.generation() < 0 || session.generation() > SessionPair.MAX_SAFE_INTEGER
                        || remaining > SessionPair.MAX_SAFE_INTEGER)) {
                    throw new IllegalStateException("validation unavailable");
                }
                results.add(active && remaining > 0
                        ? new SessionDecision(pair, true, session.generation(), remaining)
                        : SessionDecision.inactive(pair));
            }
            return List.copyOf(results);
        });
        try {
            var result = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException("validation unavailable");
            }
            return result;
        } catch (Exception failure) {
            if (failureReported.compareAndSet(false, true)) {
                long now = System.nanoTime();
                long workerStarted = progress.workerStarted;
                long sessionStarted = progress.sessionStarted;
                LoggerFactory.getLogger(CoreSessionValidationService.class).warn(
                        "event=core_chat_validation_first_delay stage={} batch_size={} elapsed_ms={} "
                                + "queue_ms={} users_ms={} sessions_completed={} current_session_ms={} "
                                + "worker_active={} worker_queued={} cause={}",
                        progress.stage, pairs.size(), TimeUnit.NANOSECONDS.toMillis(now - started),
                        TimeUnit.NANOSECONDS.toMillis((workerStarted == 0 ? now : workerStarted) - started),
                        TimeUnit.NANOSECONDS.toMillis(progress.usersNanos == 0 && workerStarted != 0
                                && progress.stage.equals("users") ? now - workerStarted : progress.usersNanos),
                        progress.sessionsCompleted,
                        sessionStarted == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(now - sessionStarted),
                        workers.getActiveCount(), workers.getQueue().size(), failure.getClass().getSimpleName());
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("validation unavailable");
        } finally {
            future.cancel(true);
        }
    }

    private static final class ValidationProgress {
        private volatile String stage = "queued";
        private volatile long workerStarted;
        private volatile long usersNanos;
        private volatile long sessionStarted;
        private volatile int sessionsCompleted;
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
