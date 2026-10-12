package com.muse.meomuneum.chat.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ChatPerformanceMetricsTest {
    @Test
    void recordsOnlyOuterLockAndKeepsRegistryUsableAfterFailure() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ChatPresenceRegistry presence = new ChatPresenceRegistry(Clock.systemUTC());
        presence.configurePerformanceMetrics(meters, true);
        assertThrows(IllegalStateException.class, () -> presence.exclusive(() -> presence.exclusive(() -> {
            throw new IllegalStateException("fixture");
        })));
        presence.exclusive(() -> {
            presence.reserve(1L, 10L, 100L);
            return null;
        });
        assertEquals(2, meters.get("performance.chat.lock.wait").timer().count());
        assertEquals(2, meters.get("performance.chat.lock.hold").timer().count());
    }
}
