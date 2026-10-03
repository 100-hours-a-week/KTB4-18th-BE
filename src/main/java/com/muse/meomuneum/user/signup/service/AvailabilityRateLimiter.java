package com.muse.meomuneum.user.signup.service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class AvailabilityRateLimiter {
    private static final int MAX_REQUESTS_PER_WINDOW = 30;
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final Map<String, ArrayDeque<Long>> requestTimesByClient = new LinkedHashMap<>(16, 0.75f, true);
    private final Clock clock;
    private long lastCleanupMillis;

    public AvailabilityRateLimiter() {
        this(Clock.systemUTC());
    }

    AvailabilityRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean isAllowed(String clientAddress) {
        long now = clock.millis();
        removeExpiredWindows(now);

        ArrayDeque<Long> requestTimes = requestTimesByClient.get(clientAddress);
        if (requestTimes == null) {
            if (requestTimesByClient.size() >= MAX_TRACKED_CLIENTS) {
                Iterator<String> iterator = requestTimesByClient.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            requestTimes = new ArrayDeque<>();
            requestTimesByClient.put(clientAddress, requestTimes);
        }

        while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= WINDOW_MILLIS) {
            requestTimes.removeFirst();
        }
        if (requestTimes.size() >= MAX_REQUESTS_PER_WINDOW) {
            return false;
        }

        requestTimes.addLast(now);
        return true;
    }

    private void removeExpiredWindows(long now) {
        if (now - lastCleanupMillis < WINDOW_MILLIS
                && (requestTimesByClient.size() < MAX_TRACKED_CLIENTS || now - lastCleanupMillis < 1_000)) {
            return;
        }

        Iterator<Map.Entry<String, ArrayDeque<Long>>> iterator = requestTimesByClient.entrySet().iterator();
        while (iterator.hasNext()) {
            ArrayDeque<Long> requestTimes = iterator.next().getValue();
            while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= WINDOW_MILLIS) {
                requestTimes.removeFirst();
            }
            if (requestTimes.isEmpty()) {
                iterator.remove();
            }
        }
        lastCleanupMillis = now;
    }
}
