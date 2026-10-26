package com.muse.meomuneum.auth.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;
import com.muse.meomuneum.user.repository.UserRepository;

/** Read only: never refreshes, rotates, revokes or extends a session. */
public final class CoreSessionValidationService implements AutoCloseable {
    private final HazelcastAuthStateStore sessions;
    private final UserRepository users;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("core-chat-validation-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public CoreSessionValidationService(HazelcastAuthStateStore sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    public List<SessionDecision> validate(List<SessionPair> pairs) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        var future = workers.submit(() -> {
            var activeUsers = users.findActiveIds(pairs.stream().map(SessionPair::userId).distinct().toList());
            List<SessionDecision> results = new ArrayList<>();
            for (SessionPair pair : pairs) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
                    throw new IllegalStateException("validation unavailable");
                }
                Instant readStarted = Instant.now();
                var session = sessions.getSession(pair.sid());
                boolean active = session != null && pair.userId() == session.userId()
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
            return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("validation unavailable");
        } finally {
            future.cancel(true);
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
