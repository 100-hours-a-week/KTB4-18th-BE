package com.muse.meomuneum.chat.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ChatPresenceEventCoalescerTest {
    @Test
    void hotRoomRequeuesBehindOtherRoomsAndReadsTheNewestPendingHint() {
        var jobs = new ArrayDeque<Runnable>();
        var received = new ArrayList<String>();
        var ref = new AtomicReference<ChatPresenceEventCoalescer>();
        var buffer = new ChatPresenceEventCoalescer(jobs::add, event -> {
            received.add(event.roomId() + ":" + event.roomSeq());
            if (event.roomId() == 1 && event.roomSeq() == 1) {
                ref.get().submit(event(1, 2));
                ref.get().submit(event(1, 3));
            }
        }, failure -> {
            throw failure;
        });
        ref.set(buffer);
        buffer.submit(event(1, 1));
        buffer.submit(event(2, 1));
        while (!jobs.isEmpty()) {
            jobs.remove().run();
        }
        assertEquals(List.of("1:1", "2:1", "1:3"), received);
    }

    @Test
    void executorRejectionDoesNotLeaveARoomPermanentlyPending() {
        var failures = new ArrayList<RuntimeException>();
        var buffer = new ChatPresenceEventCoalescer(task -> {
            throw new RejectedExecutionException();
        }, event -> {
        }, failures::add);
        buffer.submit(event(1, 1));
        buffer.submit(event(1, 2));
        assertEquals(2, failures.size());
        assertInstanceOf(RejectedExecutionException.class, failures.getFirst());
    }

    @Test
    void handlerFailureIsReportedAndDoesNotPoisonLaterWorkForThatRoom() {
        var jobs = new ArrayDeque<Runnable>();
        var failures = new ArrayList<RuntimeException>();
        var received = new ArrayList<Long>();
        var first = new AtomicBoolean(true);
        var buffer = new ChatPresenceEventCoalescer(jobs::add, event -> {
            if (first.getAndSet(false)) {
                throw new IllegalStateException();
            }
            received.add(event.roomSeq());
        }, failures::add);
        buffer.submit(event(1, 1));
        jobs.remove().run();
        buffer.submit(event(1, 2));
        jobs.remove().run();
        assertEquals(1, failures.size());
        assertEquals(List.of(2L), received);
    }

    @Test
    void closeDiscardsPendingPresenceHintsWithoutRunningTheirHandlers() {
        var jobs = new ArrayDeque<Runnable>();
        var received = new ArrayList<Long>();
        var buffer = new ChatPresenceEventCoalescer(jobs::add, event -> received.add(event.roomSeq()),
                failure -> {
                    throw failure;
                });
        buffer.submit(event(1, 1));
        buffer.close();
        buffer.submit(event(1, 2));
        jobs.remove().run();
        assertEquals(List.of(), received);
        assertEquals(0, jobs.size());
    }

    private ChatControlEvent event(long room, long seq) {
        return new ChatControlEvent(UUID.randomUUID().toString(), "CHAT_PRESENCE_CHANGED",
                "chat:" + UUID.randomUUID(), Instant.now(), 1, Map.of("room_id", room, "room_seq", seq));
    }
}
