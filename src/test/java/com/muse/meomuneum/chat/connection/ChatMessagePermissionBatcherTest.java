package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.chat.participation.ChatParticipationUnavailableException;

class ChatMessagePermissionBatcherTest {
    @Test
    void socketAndMessageChecksUseSeparateAuthorityQueriesAndZeroCannotAuthorizeAMessage() {
        var service = org.mockito.Mockito.mock(com.muse.meomuneum.chat.participation.ChatParticipationService.class);
        org.mockito.Mockito.when(service.permitsMessageBatch(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(request -> true).toList());
        org.mockito.Mockito.when(service.permitsSocketBatch(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(request -> false).toList());
        try (var batcher = new ChatMessagePermissionBatcher(service, null)) {
            assertFalse(batcher.permitsMessage(1, "s", 2, 0));
            assertFalse(batcher.permitsSocket(1, "s", 2));
            assertTrue(batcher.permitsMessage(1, "s", 2, 3));
            org.mockito.Mockito.verify(service).permitsSocketBatch(org.mockito.ArgumentMatchers.anyList());
            org.mockito.Mockito.verify(service).permitsMessageBatch(org.mockito.ArgumentMatchers.anyList());
        }
    }

    @Test
    void everyRequestGetsAFreshReadAndResultsAreNotCached() {
        var calls = new AtomicInteger();
        try (var batcher = new ChatMessagePermissionBatcher(requests -> requests.stream()
                .map(request -> calls.incrementAndGet() == 1).toList(), null)) {
            assertTrue(batcher.permitsMessage(1, "socket", 2, 3));
            assertFalse(batcher.permitsMessage(1, "socket", 2, 3));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void pendingHandlersShareTheNextFreshReadWithoutMixingTheirResults() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var batches = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        try (var batcher = new ChatMessagePermissionBatcher(requests -> {
            batches.add(requests.size());
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    assertTrue(release.await(800, TimeUnit.MILLISECONDS));
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
            }
            return requests.stream().map(request -> request.user() % 2 == 0).toList();
        }, null); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> batcher.permitsMessage(0, "first", 2, 3));
            assertTrue(entered.await(500, TimeUnit.MILLISECONDS));
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            var submitted = new CountDownLatch(8);
            for (int i = 1; i <= 8; i++) {
                int user = i;
                futures.add(workers.submit(() -> {
                    submitted.countDown();
                    return batcher.permitsMessage(user, "socket-" + user, 2, 3);
                }));
            }
            assertTrue(submitted.await(500, TimeUnit.MILLISECONDS));
            // Wait for requests to enter the bounded queue, not for a database result.
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            var queue = (java.util.Queue<?>) org.springframework.test.util.ReflectionTestUtils.getField(batcher,
                    "pending");
            while (queue.size() < 8 && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertEquals(8, queue.size());
            release.countDown();
            assertTrue(first.get(1, TimeUnit.SECONDS));
            for (int i = 0; i < futures.size(); i++) {
                assertEquals((i + 1) % 2 == 0, futures.get(i).get(1, TimeUnit.SECONDS));
            }
            assertEquals(List.of(1, 8), batches);
        } finally {
            release.countDown();
        }
    }

    @Test
    void databaseFailureAndMalformedResultsFailClosed() {
        try (var broken = new ChatMessagePermissionBatcher(requests -> {
            throw new IllegalStateException("database unavailable");
        }, null); var malformed = new ChatMessagePermissionBatcher(requests -> List.of(), null)) {
            assertThrows(ChatParticipationUnavailableException.class, () -> broken.permitsMessage(1, "s", 2, 3));
            assertThrows(ChatParticipationUnavailableException.class, () -> malformed.permitsMessage(1, "s", 2, 3));
        }
    }

    @Test
    void lateTrueNeverGrantsPermissionAndCloseRejectsNewRequests() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var batcher = new ChatMessagePermissionBatcher(requests -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return requests.stream().map(request -> true).toList();
        }, null);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = workers.submit(() -> assertThrows(ChatParticipationUnavailableException.class,
                    () -> batcher.permitsMessage(1, "s", 2, 3)));
            assertTrue(entered.await(500, TimeUnit.MILLISECONDS));
            result.get(2, TimeUnit.SECONDS);
            release.countDown();
            batcher.close();
            assertThrows(ChatParticipationUnavailableException.class, () -> batcher.permitsMessage(1, "s", 2, 3));
        } finally {
            release.countDown();
            batcher.close();
        }
    }

    @Test
    void closeCancelsHandlersAlreadyWaitingForDatabase() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var batcher = new ChatMessagePermissionBatcher(requests -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return requests.stream().map(request -> true).toList();
        }, null);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = workers.submit(() -> assertThrows(ChatParticipationUnavailableException.class,
                    () -> batcher.permitsMessage(1, "s", 2, 3)));
            assertTrue(entered.await(500, TimeUnit.MILLISECONDS));
            batcher.close();
            result.get(500, TimeUnit.MILLISECONDS);
        } finally {
            release.countDown();
            batcher.close();
        }
    }
}
