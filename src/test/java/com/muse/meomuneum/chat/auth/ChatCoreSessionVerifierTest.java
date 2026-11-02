package com.muse.meomuneum.chat.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;

class ChatCoreSessionVerifierTest {
    private final SessionPair pair = new SessionPair(1, UUID.randomUUID().toString());

    private final List<ChatCoreSessionVerifier> verifiers = new ArrayList<>();

    @AfterEach
    void closeVerifiers() {
        verifiers.forEach(ChatCoreSessionVerifier::close);
    }

    private ChatCoreSessionVerifier verifier(CoreSessionGateway gateway) {
        return verifier(gateway, System::nanoTime);
    }

    private ChatCoreSessionVerifier verifier(CoreSessionGateway gateway, LongSupplier clock) {
        var verifier = new ChatCoreSessionVerifier(gateway, clock);
        verifiers.add(verifier);
        return verifier;
    }

    @Test
    void confirmationExpiresFromRequestStartAndCannotExtendAfterPause() {
        AtomicLong time = new AtomicLong();
        var verifier = verifier(pairs -> {
            time.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
            return List.of(new SessionDecision(pair, true, 1, 10000));
        }, time::get);
        verifier.fresh(pair.userId(), pair.sid());
        time.set(TimeUnit.MILLISECONDS.toNanos(3999));
        verifier.assertValid(pair.userId(), pair.sid());
        time.set(TimeUnit.MILLISECONDS.toNanos(4000));
        assertThrows(ChatAuthUnavailableException.class, () -> verifier.assertValid(pair.userId(), pair.sid()));
        time.set(TimeUnit.MINUTES.toNanos(1));
        assertThrows(ChatAuthUnavailableException.class, () -> verifier.assertValid(pair.userId(), pair.sid()));
    }

    @Test
    void shortRemainingLifetimeAndLateResponseFailClosed() {
        AtomicLong time = new AtomicLong();
        var verifier = verifier(pairs -> List.of(new SessionDecision(pair, true, 1, 50)), time::get);
        verifier.fresh(pair.userId(), pair.sid());
        time.set(TimeUnit.MILLISECONDS.toNanos(50));
        assertThrows(ChatAuthUnavailableException.class, () -> verifier.assertValid(pair.userId(), pair.sid()));
        var late = verifier(pairs -> {
            time.addAndGet(TimeUnit.SECONDS.toNanos(1));
            return List.of(new SessionDecision(pair, true, 1, 10000));
        }, time::get);
        assertThrows(ChatAuthUnavailableException.class, () -> late.fresh(pair.userId(), pair.sid()));
        assertFalse(late.isAvailable());
    }

    @Test
    void revocationWinsOverAnAlreadyInFlightActiveResponseAndOtherSidSurvives() throws Exception {
        var other = new SessionPair(1, UUID.randomUUID().toString());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var verifier = verifier(pairs -> {
            if (pairs.contains(pair)) {
                started.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    throw new ChatAuthUnavailableException();
                }
            }
            return pairs.stream().map(p -> new SessionDecision(p, true, 1, 10000)).toList();
        });
        try (var workers = Executors.newSingleThreadExecutor()) {
            var pending = workers.submit(() -> verifier.fresh(pair.userId(), pair.sid()));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            verifier.invalidate(pair.userId(), pair.sid());
            release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertThrows(ChatRoomException.class, () -> verifier.assertValid(pair.userId(), pair.sid()));
            verifier.fresh(other.userId(), other.sid());
            verifier.assertValid(other.userId(), other.sid());
        }
    }

    @Test
    void invalidBatchAppliesNoActiveResultAndInactiveRemainsTerminal() {
        var other = new SessionPair(2, UUID.randomUUID().toString());
        var invalid = verifier(pairs -> List.of(new SessionDecision(pair, true, 1, 1000),
                new SessionDecision(pair, true, 1, 1000)));
        invalid.refresh(List.of(pair, other));
        assertThrows(ChatAuthUnavailableException.class, () -> invalid.assertValid(pair.userId(), pair.sid()));
        var inactive = verifier(pairs -> List.of(SessionDecision.inactive(pair)));
        assertThrows(ChatRoomException.class, () -> inactive.fresh(pair.userId(), pair.sid()));
        assertThrows(ChatRoomException.class, () -> inactive.fresh(pair.userId(), pair.sid()));
    }

    @Test
    void failedScopeConstructionDoesNotLeakAuthenticationIntoNextOperation() {
        var verifier = verifier(pairs -> {
            throw new ChatAuthUnavailableException();
        });
        assertThrows(ChatAuthUnavailableException.class, () -> new ChatAuthOperation(verifier, pair));
        ChatAuthOperation.check();
    }

    @Test
    void concurrentFreshRequestsUseBoundedCurrentBatchesAndSequentialFreshNeverUsesCachedResult() throws Exception {
        var sizes = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var verifier = verifier(pairs -> {
            sizes.add(pairs.size());
            return pairs.stream().map(p -> new SessionDecision(p, true, 1, 10000)).toList();
        });
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(40)) {
            var pending = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 1; i <= 40; i++) {
                var current = new SessionPair(i, UUID.randomUUID().toString());
                pending.add(workers.submit(() -> {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    verifier.fresh(current.userId(), current.sid());
                    return null;
                }));
            }
            start.countDown();
            for (var result : pending) {
                result.get(2, TimeUnit.SECONDS);
            }
        }
        assertEquals(40, sizes.stream().mapToInt(Integer::intValue).sum());
        assertTrue(sizes.size() < 40);
        assertTrue(sizes.stream().allMatch(size -> size <= 100));
        int before = sizes.size();
        verifier.fresh(pair.userId(), pair.sid());
        verifier.fresh(pair.userId(), pair.sid());
        assertEquals(before + 2, sizes.size());
    }

    @Test
    void queuedTimeCountsAgainstFreshDeadlineAndLateResultCannotAuthorize() throws Exception {
        var clock = new AtomicLong();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var other = new SessionPair(2, UUID.randomUUID().toString());
        var calls = new AtomicInteger();
        var verifier = verifier(pairs -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ChatAuthUnavailableException();
                }
            }
            return pairs.stream().map(p -> new SessionDecision(p, true, 1, 10000)).toList();
        }, clock::get);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> verifier.fresh(pair.userId(), pair.sid()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = workers.submit(() -> verifier.fresh(other.userId(), other.sid()));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            var queue = (java.util.concurrent.BlockingQueue<?>) org.springframework.test.util.ReflectionTestUtils
                    .getField(verifier, "freshRequests");
            while (queue.isEmpty() && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertEquals(1, queue.size());
            clock.set(TimeUnit.MILLISECONDS.toNanos(1000));
            release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> second.get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertThrows(ChatAuthUnavailableException.class, () -> verifier.assertValid(other.userId(), other.sid()));
        }
    }

    @Test
    void closeRejectsPendingAndFutureRequestsWithoutApplyingLateActiveResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var verifier = verifier(pairs -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return pairs.stream().map(p -> new SessionDecision(p, true, 1, 10000)).toList();
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var pending = worker.submit(() -> verifier.fresh(pair.userId(), pair.sid()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            verifier.close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertThrows(ChatAuthUnavailableException.class, () -> verifier.fresh(pair.userId(), pair.sid()));
            assertThrows(ChatAuthUnavailableException.class, () -> verifier.assertValid(pair.userId(), pair.sid()));
            assertFalse(verifier.isAvailable());
            assertFalse(((Thread) org.springframework.test.util.ReflectionTestUtils.getField(verifier, "freshWorker"))
                    .isAlive());
        }
    }
}
