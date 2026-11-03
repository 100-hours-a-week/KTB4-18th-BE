package com.muse.meomuneum.auth.directv2.security;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;

@Component
public class DirectAuthNonceAdmissionGuard {
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Permit() {
        }

        @Override
        public void close() {
            if (!open.compareAndSet(true, false)) {
                throw DirectAuthFailure.unavailable();
            }
            permits.release();
        }
    }

    private final Semaphore permits = new Semaphore(32);
    private final ReentrantLock bucket = new ReentrantLock();
    private final LongSupplier clock;
    private long last;
    private double tokens = 1200;
    private volatile boolean failed;

    public DirectAuthNonceAdmissionGuard() {
        this(System::nanoTime);
    }

    public DirectAuthNonceAdmissionGuard(LongSupplier clock) {
        this.clock = clock;
        this.last = clock.getAsLong();
    }

    public Permit acquire() {
        if (!permits.tryAcquire()) {
            throw DirectAuthFailure.unavailable();
        }
        boolean accepted = false;
        try {
            if (!bucket.tryLock()) {
                throw DirectAuthFailure.unavailable();
            }
            try {
                long now;
                try {
                    now = clock.getAsLong();
                } catch (RuntimeException clockFailure) {
                    failed = true;
                    throw DirectAuthFailure.unavailable();
                }
                long elapsed = Math.subtractExact(now, last);
                if (failed || elapsed < 0) {
                    failed = true;
                    throw DirectAuthFailure.unavailable();
                }
                tokens = Math.min(1200, tokens + elapsed * (1200.0 / 1_000_000_000.0));
                last = now;
                if (tokens < 1) {
                    throw DirectAuthFailure.unavailable();
                }
                tokens -= 1;
                accepted = true;
                return new Permit();
            } finally {
                bucket.unlock();
            }
        } catch (ArithmeticException invalid) {
            failed = true;
            throw DirectAuthFailure.unavailable();
        } finally {
            if (!accepted) {
                permits.release();
            }
        }
    }
}
