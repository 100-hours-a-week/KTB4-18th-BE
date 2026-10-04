package com.muse.meomuneum.user.signup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class UserAvailabilityRateLimiterTest {
    @Test
    void keepsLimitAcrossMinuteBoundaryUntilEachRequestExpires() {
        AtomicLong currentMillis = new AtomicLong(59_900);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(invocation -> currentMillis.get());
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        when(clock.instant()).thenAnswer(invocation -> Instant.ofEpochMilli(currentMillis.get()));
        AvailabilityRateLimiter limiter = new AvailabilityRateLimiter(clock);

        for (int request = 0; request < 30; request++) {
            assertThat(limiter.isAllowed("192.0.2.1")).isTrue();
        }

        currentMillis.set(60_100);
        assertThat(limiter.isAllowed("192.0.2.1")).isFalse();

        currentMillis.set(119_900);
        assertThat(limiter.isAllowed("192.0.2.1")).isTrue();
    }
}
