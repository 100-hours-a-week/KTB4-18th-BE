package com.muse.meomuneum.chat.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.chat.room.exception.ChatRoomException;
import com.muse.meomuneum.global.security.SessionDecision;
import com.muse.meomuneum.global.security.SessionPair;

class ChatCoreSessionVerifierTest {
    private final SessionPair pair = new SessionPair(1, UUID.randomUUID().toString());

    @Test
    void confirmationExpiresFromRequestStartAndCannotExtendAfterPause() {
        AtomicLong time = new AtomicLong();
        var verifier = new ChatCoreSessionVerifier(pairs -> {
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
        var verifier = new ChatCoreSessionVerifier(pairs -> List.of(new SessionDecision(pair, true, 1, 50)), time::get);
        verifier.fresh(pair.userId(), pair.sid());
        time.set(TimeUnit.MILLISECONDS.toNanos(50));
        assertThrows(ChatAuthUnavailableException.class, () -> verifier.assertValid(pair.userId(), pair.sid()));
        var late = new ChatCoreSessionVerifier(pairs -> {
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
        var verifier = new ChatCoreSessionVerifier(pairs -> {
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
        var invalid = new ChatCoreSessionVerifier(pairs -> List.of(new SessionDecision(pair, true, 1, 1000),
                new SessionDecision(pair, true, 1, 1000)));
        invalid.refresh(List.of(pair, other));
        assertThrows(ChatAuthUnavailableException.class, () -> invalid.assertValid(pair.userId(), pair.sid()));
        var inactive = new ChatCoreSessionVerifier(pairs -> List.of(SessionDecision.inactive(pair)));
        assertThrows(ChatRoomException.class, () -> inactive.fresh(pair.userId(), pair.sid()));
        assertThrows(ChatRoomException.class, () -> inactive.fresh(pair.userId(), pair.sid()));
    }

    @Test
    void failedScopeConstructionDoesNotLeakAuthenticationIntoNextOperation() {
        var verifier = new ChatCoreSessionVerifier(pairs -> {
            throw new ChatAuthUnavailableException();
        });
        assertThrows(ChatAuthUnavailableException.class, () -> new ChatAuthOperation(verifier, pair));
        ChatAuthOperation.check();
    }
}
