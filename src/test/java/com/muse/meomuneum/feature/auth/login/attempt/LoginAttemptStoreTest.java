package com.muse.meomuneum.feature.auth.login.attempt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptStoreTest {

    @Test
    void doublesLockoutDurationForEachTenFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-20T00:00:00Z"));
        LoginAttemptStore store = new LoginAttemptStore(clock);
        String email = "member@example.com";

        assertLockout(store, clock, email, 5);
        assertLockout(store, clock, email, 10);
        assertLockout(store, clock, email, 20);
        assertLockout(store, clock, email, 40);
    }

    @Test
    void resetsTheFailureAndLockoutCountAfterSuccessfulLogin() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-20T00:00:00Z"));
        LoginAttemptStore store = new LoginAttemptStore(clock);
        String email = "member@example.com";

        assertLockout(store, clock, email, 5);
        store.clearAfterSuccessfulLogin(email);
        assertFalse(store.isBlocked(email));

        assertLockout(store, clock, email, 5);
    }

    private void assertLockout(LoginAttemptStore store, MutableClock clock, String email, long minutes) {
        for (int attempt = 0; attempt < LoginAttemptStore.MAX_FAILED_ATTEMPTS; attempt++) {
            store.recordFailure(email);
        }
        assertTrue(store.isBlocked(email));
        clock.advanceSeconds(minutes * 60);
        assertFalse(store.isBlocked(email));
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
