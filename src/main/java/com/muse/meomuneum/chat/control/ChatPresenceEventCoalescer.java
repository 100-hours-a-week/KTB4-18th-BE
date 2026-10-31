package com.muse.meomuneum.chat.control;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Presence hints read the latest DB snapshot; terminal events and messages never enter this buffer. */
public final class ChatPresenceEventCoalescer {
    private final Executor executor;
    private final Consumer<ChatControlEvent> handler;
    private final Consumer<RuntimeException> failed;
    private final Map<Long, Pending> pending = new HashMap<>();
    private boolean closed;

    public ChatPresenceEventCoalescer(Executor executor, Consumer<ChatControlEvent> handler,
            Consumer<RuntimeException> failed) {
        this.executor = executor;
        this.handler = handler;
        this.failed = failed;
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
            var item = new Pending(event);
            pending.put(event.roomId(), item);
            failure = schedule(item);
        }
        if (failure != null) {
            failed.accept(failure);
        }
    }

    // Called under the buffer lock: map entries are bounded by the executor's queued/running tasks.
    private RuntimeException schedule(Pending item) {
        try {
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
                // Requeue behind other work so a hot room cannot monopolize a receiver thread.
                failure = schedule(item);
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

        private Pending(ChatControlEvent event) {
            room = event.roomId();
            latest = event;
        }
    }
}
