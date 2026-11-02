package com.muse.meomuneum.chat.control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Presence hints read the latest DB snapshot; terminal events and messages never enter this buffer. */
public final class ChatPresenceEventCoalescer {
    private final Executor executor;
    private final Consumer<ChatControlEvent> handler;
    private final Consumer<RuntimeException> failed;
    private final long windowNanos;
    private final int maxRooms;
    private final LongSupplier clock;
    private final Map<Long, Pending> pending = new HashMap<>();
    private boolean closed;

    public ChatPresenceEventCoalescer(Executor executor, Consumer<ChatControlEvent> handler,
            Consumer<RuntimeException> failed) {
        this(executor, handler, failed, 0, 256, System::nanoTime);
    }

    public ChatPresenceEventCoalescer(Executor executor, Consumer<ChatControlEvent> handler,
            Consumer<RuntimeException> failed, long windowNanos, int maxRooms, LongSupplier clock) {
        if (windowNanos < 0 || maxRooms < 1) {
            throw new IllegalArgumentException();
        }
        this.executor = executor;
        this.handler = handler;
        this.failed = failed;
        this.windowNanos = windowNanos;
        this.maxRooms = maxRooms;
        this.clock = clock;
    }

    public void submit(ChatControlEvent event) {
        RuntimeException failure;
        synchronized (pending) {
            if (closed) {
                return;
            }
            var existing = pending.get(event.roomId());
            if (existing != null) {
                existing.latest = event;
                return;
            }
            if (pending.size() >= maxRooms) {
                failure = new RejectedExecutionException("presence room buffer full");
            } else {
                var item = new Pending(event, clock.getAsLong() + windowNanos);
                pending.put(event.roomId(), item);
                failure = windowNanos == 0 ? schedule(item) : null;
            }
        }
        if (failure != null) {
            failed.accept(failure);
        }
    }

    /** Called by the existing dispatch scheduler; no sleeping receiver or per-hint timer is created. */
    public void flushDue() {
        var failures = new ArrayList<RuntimeException>();
        synchronized (pending) {
            if (closed) {
                return;
            }
            long now = clock.getAsLong();
            for (var item : new ArrayList<>(pending.values())) {
                if (!item.scheduled && now - item.due >= 0) {
                    var failure = schedule(item);
                    if (failure != null) {
                        failures.add(failure);
                    }
                }
            }
        }
        failures.forEach(failed);
    }

    // Called under the buffer lock. Both buffered rooms and executor tasks have explicit bounds.
    private RuntimeException schedule(Pending item) {
        try {
            item.scheduled = true;
            executor.execute(() -> drain(item));
            return null;
        } catch (RuntimeException failure) {
            pending.remove(item.room, item);
            return failure;
        }
    }

    private void drain(Pending item) {
        ChatControlEvent event;
        synchronized (pending) {
            if (closed) {
                return;
            }
            event = item.latest;
            item.latest = null;
        }
        RuntimeException failure = null;
        try {
            handler.accept(event);
        } catch (RuntimeException unavailable) {
            failure = unavailable;
        }
        synchronized (pending) {
            if (!closed && failure == null && item.latest != null) {
                if (windowNanos == 0) {
                    failure = schedule(item);
                } else {
                    item.scheduled = false;
                    item.due = clock.getAsLong() + windowNanos;
                }
            } else {
                pending.remove(item.room, item);
            }
        }
        if (failure != null) {
            failed.accept(failure);
        }
    }

    public void close() {
        synchronized (pending) {
            closed = true;
            pending.clear();
        }
    }

    private static final class Pending {
        private final long room;
        private ChatControlEvent latest;
        private long due;
        private boolean scheduled;

        private Pending(ChatControlEvent event, long due) {
            room = event.roomId();
            latest = event;
            this.due = due;
        }
    }
}
