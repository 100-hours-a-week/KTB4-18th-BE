package com.muse.meomuneum.chat.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.muse.meomuneum.chat.room.exception.ChatRoomErrorCode;
import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;

/** All authorization reads are local; network validation must happen before presence/DB locks. */
public final class ChatCoreSessionVerifier implements ChatSessionVerifier {
    private volatile Consumer<SessionPair> onInactive = ignored -> {
    };
    private final CoreSessionGateway gateway;
    private final LongSupplier nanoTime;
    private final Map<SessionPair, State> states = new ConcurrentHashMap<>();
    private final AtomicBoolean available = new AtomicBoolean();

    public ChatCoreSessionVerifier(CoreSessionGateway gateway) {
        this(gateway, System::nanoTime);
    }

    public ChatCoreSessionVerifier(CoreSessionGateway gateway, LongSupplier nanoTime) {
        this.gateway = gateway;
        this.nanoTime = nanoTime;
    }

    @Override
    public void fresh(long userId, String sid) {
        SessionPair pair = pair(userId, sid);
        State state = state(pair);
        CompletableFuture<Void> pending;
        boolean owner;
        synchronized (state) {
            terminal(state);
            owner = state.inFlight == null;
            if (owner) {
                state.inFlight = new CompletableFuture<>();
            }
            pending = state.inFlight;
        }
        if (owner) {
            validate(List.of(pair), List.of(state));
        }
        try {
            pending.get(1, TimeUnit.SECONDS);
        } catch (Exception failure) {
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
        for (int from = 0; from < pairs.size(); from += 100) {
            List<SessionPair> batch = new ArrayList<>();
            List<State> selected = new ArrayList<>();
            for (SessionPair pair : pairs.subList(from, Math.min(from + 100, pairs.size()))) {
                State state = state(pair);
                synchronized (state) {
                    if (!state.denied && state.inFlight == null) {
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
        long started = nanoTime.getAsLong();
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
            available.set(true);
        } catch (RuntimeException failure) {
            available.set(false);
            for (State state : selected) {
                synchronized (state) {
                    state.inFlight.completeExceptionally(new ChatAuthUnavailableException());
                    state.inFlight = null;
                }
            }
        }
    }

    @Override
    public void assertValid(long userId, String sid) {
        State state = states.get(pair(userId, sid));
        if (state == null) {
            throw new ChatAuthUnavailableException();
        }
        synchronized (state) {
            terminal(state);
            if (!state.confirmed || nanoTime.getAsLong() - state.deadline >= 0) {
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
        available.set(healthy);
    }

    public boolean isAvailable() {
        return available.get();
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

    private static final class State {
        private boolean denied;
        private boolean confirmed;
        private long deadline;
        private long touched;
        private CompletableFuture<Void> inFlight;
    }
}
