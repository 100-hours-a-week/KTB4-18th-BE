package com.muse.meomuneum.chat.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;

/** All authorization reads are local; network validation must happen before presence/DB locks. */
public final class ChatCoreSessionVerifier implements ChatSessionVerifier, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ChatCoreSessionVerifier.class);
    private final AtomicBoolean validationFailureLogged = new AtomicBoolean();
    private final AtomicBoolean expiredLogged = new AtomicBoolean();
    private final AtomicBoolean pendingFailureLogged = new AtomicBoolean();
    private volatile Consumer<SessionPair> onInactive = ignored -> {
    };
    private final CoreSessionGateway gateway;
    private final LongSupplier nanoTime;
    private final Map<SessionPair, State> states = new ConcurrentHashMap<>();
    private final AtomicBoolean available = new AtomicBoolean();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final ArrayBlockingQueue<FreshRequest> freshRequests = new ArrayBlockingQueue<>(200);
    private final Thread freshWorker;

    private record FreshRequest(SessionPair pair, State state, CompletableFuture<Void> result, long started) {
    }

    public ChatCoreSessionVerifier(CoreSessionGateway gateway) {
        this(gateway, System::nanoTime);
    }

    public ChatCoreSessionVerifier(CoreSessionGateway gateway, LongSupplier nanoTime) {
        this.gateway = gateway;
        this.nanoTime = nanoTime;
        freshWorker = Thread.ofPlatform().daemon().name("chat-fresh-auth-batch").start(this::freshLoop);
    }

    @Override
    public void fresh(long userId, String sid) {
        long started = nanoTime.getAsLong();
        if (!open.get()) {
            throw new ChatAuthUnavailableException();
        }
        SessionPair pair = pair(userId, sid);
        State state = state(pair);
        CompletableFuture<Void> pending;
        boolean owner;
        synchronized (state) {
            if (!open.get()) {
                throw new ChatAuthUnavailableException();
            }
            terminal(state);
            owner = state.inFlight == null;
            if (owner) {
                state.inFlight = new CompletableFuture<>();
            }
            pending = state.inFlight;
        }
        if (owner) {
            var request = new FreshRequest(pair, state, pending, started);
            if (!open.get() || !freshRequests.offer(request)) {
                rejectFresh(request);
            }
            if (!open.get() && freshRequests.remove(request)) {
                rejectFresh(request);
            }
        }
        try {
            long remaining = TimeUnit.SECONDS.toNanos(1) - (nanoTime.getAsLong() - started);
            if (remaining <= 0) {
                throw new ChatAuthUnavailableException();
            }
            pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (Exception failure) {
            if (pendingFailureLogged.compareAndSet(false, true)) {
                log.warn("event=chat_auth_first_pending_failure cause={}", failure.getClass().getSimpleName());
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            synchronized (state) {
                terminal(state);
            }
            throw new ChatAuthUnavailableException();
        }
        assertValid(userId, sid);
    }

    public void refresh(List<SessionPair> pairs) {
        if (!open.get()) {
            return;
        }
        for (int from = 0; from < pairs.size(); from += 100) {
            List<SessionPair> batch = new ArrayList<>();
            List<State> selected = new ArrayList<>();
            for (SessionPair pair : pairs.subList(from, Math.min(from + 100, pairs.size()))) {
                State state = state(pair);
                synchronized (state) {
                    if (open.get() && !state.denied && state.inFlight == null) {
                        state.inFlight = new CompletableFuture<>();
                        batch.add(pair);
                        selected.add(state);
                    }
                }
            }
            if (!batch.isEmpty()) {
                validate(batch, selected);
            }
        }
    }

    private void validate(List<SessionPair> pairs, List<State> selected) {
        var expected = selected.stream().map(state -> {
            synchronized (state) {
                return state.inFlight;
            }
        }).toList();
        validate(pairs, selected, expected, nanoTime.getAsLong());
    }

    private void validate(List<SessionPair> pairs, List<State> selected,
            List<CompletableFuture<Void>> expected, long started) {
        try {
            List<SessionDecision> decisions = gateway.validate(pairs);
            if (nanoTime.getAsLong() - started >= TimeUnit.SECONDS.toNanos(1) || decisions.size() != pairs.size()) {
                throw new ChatAuthUnavailableException();
            }
            // Validate the complete batch before applying any ACTIVE result.
            for (int i = 0; i < decisions.size(); i++) {
                SessionDecision decision = decisions.get(i);
                if (!decision.pair().equals(pairs.get(i)) || (decision.active()
                        && (decision.remainingMillis() < 1 || decision.generation() < 0))) {
                    throw new ChatAuthUnavailableException();
                }
            }
            for (int i = 0; i < decisions.size(); i++) {
                State state = selected.get(i);
                SessionDecision decision = decisions.get(i);
                synchronized (state) {
                    if (!open.get() || state.inFlight != expected.get(i) || expected.get(i) == null
                            || expected.get(i).isDone()) {
                        continue;
                    }
                    state.touched = nanoTime.getAsLong();
                    if (!decision.active()) {
                        state.denied = true;
                        onInactive.accept(decision.pair());
                    } else if (!state.denied && states.get(pairs.get(i)) == state) {
                        state.deadline = started
                                + TimeUnit.MILLISECONDS.toNanos(Math.min(4000, decision.remainingMillis()));
                        state.confirmed = true;
                    }
                    state.inFlight.complete(null);
                    state.inFlight = null;
                }
            }
            available.set(open.get());
        } catch (RuntimeException failure) {
            if (validationFailureLogged.compareAndSet(false, true)) {
                log.warn("event=chat_auth_first_validation_failure batch_size={} elapsed_ms={} cause={}",
                        pairs.size(), TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - started),
                        failure.getClass().getSimpleName());
            }
            available.set(false);
            for (int i = 0; i < selected.size(); i++) {
                State state = selected.get(i);
                synchronized (state) {
                    if (state.inFlight != null && state.inFlight == expected.get(i)) {
                        state.inFlight.completeExceptionally(new ChatAuthUnavailableException());
                        state.inFlight = null;
                    }
                }
            }
        }
    }

    @Override
    public void assertValid(long userId, String sid) {
        if (!open.get()) {
            throw new ChatAuthUnavailableException();
        }
        State state = states.get(pair(userId, sid));
        if (state == null) {
            throw new ChatAuthUnavailableException();
        }
        synchronized (state) {
            terminal(state);
            if (!state.confirmed || nanoTime.getAsLong() - state.deadline >= 0) {
                if (expiredLogged.compareAndSet(false, true)) {
                    log.warn("event=chat_auth_first_invalid_permit confirmed={} in_flight={} expired_by_ms={}",
                            state.confirmed, state.inFlight != null,
                            state.confirmed ? TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - state.deadline) : 0);
                }
                throw new ChatAuthUnavailableException();
            }
            state.touched = nanoTime.getAsLong();
        }
    }

    @Override
    public void invalidate(long userId, String sid) {
        SessionPair pair = pair(userId, sid);
        State state = states.get(pair);
        if (state != null) {
            synchronized (state) {
                state.denied = true;
                state.confirmed = false;
                state.touched = nanoTime.getAsLong();
            }
        }
        onInactive.accept(pair);
    }

    public void onInactive(Consumer<SessionPair> callback) {
        onInactive = callback;
    }

    public void serviceAvailable(boolean healthy) {
        available.set(open.get() && healthy);
    }

    public boolean isAvailable() {
        return open.get() && available.get();
    }

    public List<SessionPair> recentPairs() {
        return states.entrySet().stream().filter(entry -> {
            synchronized (entry.getValue()) {
                return !entry.getValue().denied;
            }
        }).map(Map.Entry::getKey).toList();
    }

    public void expireUnused(List<SessionPair> referenced) {
        long now = nanoTime.getAsLong();
        states.entrySet().removeIf(entry -> {
            State value = entry.getValue();
            synchronized (value) {
                return value.inFlight == null && now - value.touched > TimeUnit.SECONDS.toNanos(30)
                        && !referenced.contains(entry.getKey());
            }
        });
    }

    private synchronized State state(SessionPair pair) {
        State existing = states.get(pair);
        if (existing != null) {
            return existing;
        }
        if (states.size() >= 20000) {
            throw new ChatAuthUnavailableException();
        }
        State created = new State();
        created.touched = nanoTime.getAsLong();
        states.put(pair, created);
        return created;
    }

    private SessionPair pair(long userId, String sid) {
        try {
            return new SessionPair(userId, sid);
        } catch (IllegalArgumentException invalid) {
            throw new ChatRoomException(ChatRoomErrorCode.AUTH_REQUIRED);
        }
    }

    private void terminal(State state) {
        if (state.denied) {
            throw new ChatRoomException(ChatRoomErrorCode.AUTH_REQUIRED);
        }
    }

    private void freshLoop() {
        try {
            while (open.get()) {
                var requests = new ArrayList<FreshRequest>();
                requests.add(freshRequests.take());
                // Only coalesce requests already waiting; never reuse a previous validation result.
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                freshRequests.drainTo(requests, 99);
                var active = requests.stream().filter(request -> {
                    if (!open.get() || request.result().isDone()
                            || nanoTime.getAsLong() - request.started() >= TimeUnit.SECONDS.toNanos(1)) {
                        rejectFresh(request);
                        return false;
                    }
                    return true;
                }).toList();
                if (!active.isEmpty()) {
                    long started = active.stream().mapToLong(FreshRequest::started).min().orElseThrow();
                    validate(active.stream().map(FreshRequest::pair).toList(),
                            active.stream().map(FreshRequest::state).toList(),
                            active.stream().map(FreshRequest::result).toList(), started);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            FreshRequest request;
            while ((request = freshRequests.poll()) != null) {
                rejectFresh(request);
            }
        }
    }

    private void rejectFresh(FreshRequest request) {
        synchronized (request.state()) {
            request.result().completeExceptionally(new ChatAuthUnavailableException());
            if (request.state().inFlight == request.result()) {
                request.state().inFlight = null;
            }
        }
    }

    @Override
    public void close() {
        if (!open.getAndSet(false)) {
            return;
        }
        available.set(false);
        freshWorker.interrupt();
        states.values().forEach(state -> {
            synchronized (state) {
                state.confirmed = false;
                if (state.inFlight != null) {
                    state.inFlight.completeExceptionally(new ChatAuthUnavailableException());
                    state.inFlight = null;
                }
            }
        });
        try {
            freshWorker.join(2500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class State {
        private boolean denied;
        private boolean confirmed;
        private long deadline;
        private long touched;
        private CompletableFuture<Void> inFlight;
    }
}
