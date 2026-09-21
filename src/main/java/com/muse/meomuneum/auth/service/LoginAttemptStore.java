package com.muse.meomuneum.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class LoginAttemptStore {

    static final int MAX_FAILED_ATTEMPTS = 10;
    static final long INITIAL_LOCKOUT_MINUTES = 5;

    private final ConcurrentHashMap<String, LoginAttemptState> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptStore() {
        this(Clock.systemUTC());
    }

    LoginAttemptStore(Clock clock) {
        this.clock = clock;
    }

    public boolean isBlocked(String email) {
        LoginAttemptState state = states.get(email);
        return state != null && state.isBlocked(clock.instant());
    }

    public void recordFailure(String email) {
        states.compute(email, (key, current) -> {
            LoginAttemptState state = current == null ? new LoginAttemptState() : current;
            state.recordFailure(clock.instant());
            return state;
        });
    }

    public void clearAfterSuccessfulLogin(String email) {
        states.remove(email);
    }

    private static final class LoginAttemptState {

        private int consecutiveFailures;
        private int lockoutCount;
        private Instant lockedUntil = Instant.EPOCH;

        private void recordFailure(Instant now) {
            if (isBlocked(now)) {
                return;
            }

            consecutiveFailures++;
            if (consecutiveFailures < MAX_FAILED_ATTEMPTS) {
                return;
            }

            consecutiveFailures = 0;
            lockoutCount++;
            lockedUntil = now.plus(lockoutMinutes(), ChronoUnit.MINUTES);
        }

        private boolean isBlocked(Instant now) {
            return lockedUntil.isAfter(now);
        }

        private long lockoutMinutes() {
            return INITIAL_LOCKOUT_MINUTES * (1L << (lockoutCount - 1));
        }
    }
}
