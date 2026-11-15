package com.muse.meomuneum.auth.directv2.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.auth.directv2.model.DirectAuthFailure;
import com.muse.meomuneum.auth.directv2.security.DirectAuthNonceAdmissionGuard;

class DirectAuthNonceAdmissionTest {
    @Test
    void sharedBurstAndRefillBoundStartsAndFailuresNeverRefundTokens() {
        var time = new AtomicLong();
        var guard = new DirectAuthNonceAdmissionGuard(time::get);
        for (int index = 0; index < 1200; index++) {
            try (var permit = guard.acquire()) {
                assertThat(permit).isNotNull();
            }
        }
        assertThatThrownBy(guard::acquire).isInstanceOf(DirectAuthFailure.class);
        time.set(1_000_000_000);
        for (int index = 0; index < 1200; index++) {
            guard.acquire().close();
        }
        assertThatThrownBy(guard::acquire).isInstanceOf(DirectAuthFailure.class);
        time.addAndGet(833_333);
        assertThatThrownBy(guard::acquire).isInstanceOf(DirectAuthFailure.class);
        time.incrementAndGet();
        guard.acquire().close();
        assertThatThrownBy(guard::acquire).isInstanceOf(DirectAuthFailure.class);
    }

    @Test
    void thirtyTwoNonwaitingPermitsReleaseOnEveryFailureAndCannotBeDoubleReleased() {
        var guard = new DirectAuthNonceAdmissionGuard(() -> 0);
        var held = new ArrayList<DirectAuthNonceAdmissionGuard.Permit>();
        for (int index = 0; index < 32; index++) {
            held.add(guard.acquire());
        }
        assertThatThrownBy(guard::acquire).isInstanceOf(DirectAuthFailure.class);
        held.forEach(DirectAuthNonceAdmissionGuard.Permit::close);
        assertThatThrownBy(held.getFirst()::close).isInstanceOf(DirectAuthFailure.class);
        for (int index = 0; index < 32; index++) {
            try (var permit = guard.acquire()) {
                throw new IllegalStateException("test failure");
            } catch (IllegalStateException expected) {
                assertThat(expected.getMessage()).isEqualTo("test failure");
            }
        }
        for (int index = 0; index < 32; index++) {
            held.set(index, guard.acquire());
        }
        assertThatThrownBy(guard::acquire).isInstanceOf(DirectAuthFailure.class);
        held.forEach(DirectAuthNonceAdmissionGuard.Permit::close);
    }

    @Test
    void concurrentStartsStayBoundedAndClockFaultStaysClosed() throws Exception {
        var guard = new DirectAuthNonceAdmissionGuard(() -> 0);
        var admitted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(32)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < 2400; index++) {
                futures.add(pool.submit(() -> {
                    try (var permit = guard.acquire()) {
                        admitted.incrementAndGet();
                    } catch (DirectAuthFailure rejected) {
                        assertThat(rejected.status()).isEqualTo(503);
                    }
                }));
            }
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(admitted.get()).isBetween(1, 1200);
        var clock = new AtomicLong(10);
        var broken = new DirectAuthNonceAdmissionGuard(clock::get);
        clock.set(9);
        assertThatThrownBy(broken::acquire).isInstanceOf(DirectAuthFailure.class);
        clock.set(11);
        assertThatThrownBy(broken::acquire).isInstanceOf(DirectAuthFailure.class);
        var throwing = new AtomicInteger();
        var fault = new DirectAuthNonceAdmissionGuard(() -> {
            if (throwing.incrementAndGet() > 1) {
                throw new IllegalStateException();
            }
            return 0;
        });
        assertThatThrownBy(fault::acquire).isInstanceOf(DirectAuthFailure.class);
    }
}
