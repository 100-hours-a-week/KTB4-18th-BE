package com.muse.meomuneum.chat.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

import com.muse.meomuneum.chat.message.relay.ChatDeliveryMetrics;
import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.chat.participation.ChatParticipationService.MessagePermission;
import com.muse.meomuneum.chat.participation.ChatParticipationUnavailableException;

/** Coalesces only current handler requests into a new primary read; never caches authorization results. */
public final class ChatMessagePermissionBatcher implements AutoCloseable {
    public static final int MAX_BATCH_SIZE = 32;
    private record Pending(MessagePermission permission, long deadline, CompletableFuture<Boolean> result) {
    }

    private final ArrayBlockingQueue<Pending> pending = new ArrayBlockingQueue<>(MAX_BATCH_SIZE * 2);
    private final AtomicReference<List<Pending>> inFlight = new AtomicReference<>(List.of());
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Function<List<MessagePermission>, List<Boolean>> verify;
    private final ChatDeliveryMetrics metrics;
    private final Thread worker;

    public ChatMessagePermissionBatcher(ChatParticipationService participation, ChatDeliveryMetrics metrics) {
        this(requests -> {
            var results = new ArrayList<Boolean>(java.util.Collections.nCopies(requests.size(), false));
            for (boolean messages : new boolean[]{true, false}) {
                var indexes = new ArrayList<Integer>();
                var group = new ArrayList<MessagePermission>();
                for (int i = 0; i < requests.size(); i++) {
                    if ((requests.get(i).message() != 0) == messages) {
                        indexes.add(i);
                        group.add(requests.get(i));
                    }
                }
                if (!group.isEmpty()) {
                    var allowed = messages
                            ? participation.permitsMessageBatch(group)
                            : participation.permitsSocketBatch(group);
                    for (int i = 0; i < group.size(); i++) {
                        results.set(indexes.get(i), allowed.get(i));
                    }
                }
            }
            return results;
        }, metrics);
    }

    ChatMessagePermissionBatcher(Function<List<MessagePermission>, List<Boolean>> verify, ChatDeliveryMetrics metrics) {
        this.verify = verify;
        this.metrics = metrics;
        worker = Thread.ofPlatform().daemon().name("chat-permission-batch").start(this::run);
    }

    public boolean permitsMessage(long user, String socket, long room, long message) {
        if (message <= 0) {
            return false;
        }
        return permits(user, socket, room, message);
    }

    public boolean permitsSocket(long user, String socket, long room) {
        return permits(user, socket, room, 0);
    }

    private boolean permits(long user, String socket, long room, long message) {
        long started = System.nanoTime();
        var request = new Pending(new MessagePermission(user, socket, room, message),
                started + TimeUnit.SECONDS.toNanos(1), new CompletableFuture<>());
        try {
            if (!running.get() || !pending.offer(request)) {
                throw new ChatParticipationUnavailableException();
            }
            if (!running.get() && pending.remove(request)) {
                cancel(request);
            }
            long remaining = request.deadline() - System.nanoTime();
            if (remaining <= 0) {
                throw new ChatParticipationUnavailableException();
            }
            boolean permitted = request.result().get(remaining, TimeUnit.NANOSECONDS);
            if (!running.get() || request.deadline() - System.nanoTime() <= 0) {
                throw new ChatParticipationUnavailableException();
            }
            return permitted;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancel(request);
            throw new ChatParticipationUnavailableException();
        } catch (TimeoutException | ExecutionException failure) {
            cancel(request);
            throw new ChatParticipationUnavailableException();
        } finally {
            ChatDeliveryMetrics.record(metrics, message == 0
                    ? ChatDeliveryMetrics.Stage.PRESENCE_PERMISSION_BATCH_WAIT
                    : ChatDeliveryMetrics.Stage.MESSAGE_PERMISSION_BATCH_WAIT, started);
        }
    }

    private void run() {
        try {
            while (running.get()) {
                var batch = new ArrayList<Pending>();
                batch.add(pending.take());
                LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
                pending.drainTo(batch, MAX_BATCH_SIZE - 1);
                var active = batch.stream().filter(request -> {
                    if (request.deadline() - System.nanoTime() <= 0 || request.result().isDone()) {
                        cancel(request);
                        return false;
                    }
                    return true;
                }).toList();
                inFlight.set(active);
                try {
                    if (!running.get()) {
                        active.forEach(ChatMessagePermissionBatcher::cancel);
                    } else if (!active.isEmpty()) {
                        var results = verify.apply(active.stream().map(Pending::permission).toList());
                        if (results.size() != active.size()) {
                            throw new IllegalStateException("Permission result count mismatch");
                        }
                        for (int index = 0; index < active.size(); index++) {
                            var request = active.get(index);
                            if (!running.get() || request.deadline() - System.nanoTime() <= 0) {
                                cancel(request);
                            } else {
                                request.result().complete(Boolean.TRUE.equals(results.get(index)));
                            }
                        }
                    }
                } catch (RuntimeException failure) {
                    active.forEach(request -> request.result().completeExceptionally(failure));
                } finally {
                    inFlight.set(List.of());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
            pending.forEach(ChatMessagePermissionBatcher::cancel);
            pending.clear();
        }
    }

    private static void cancel(Pending request) {
        request.result().completeExceptionally(new ChatParticipationUnavailableException());
    }

    @Override
    public void close() {
        running.set(false);
        inFlight.get().forEach(ChatMessagePermissionBatcher::cancel);
        pending.forEach(ChatMessagePermissionBatcher::cancel);
        pending.clear();
        worker.interrupt();
        try {
            worker.join(2500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
