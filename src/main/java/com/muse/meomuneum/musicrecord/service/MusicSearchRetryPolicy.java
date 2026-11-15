package com.muse.meomuneum.musicrecord.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MusicSearchRetryPolicy {
    private final long baseMillis;
    private final long maxMillis;
    private final LongUnaryOperator random;

    @org.springframework.beans.factory.annotation.Autowired
    public MusicSearchRetryPolicy(@Value("${music.search-storage.retry-base-ms:5000}") long baseMillis,
            @Value("${music.search-storage.retry-max-ms:300000}") long maxMillis) {
        this(baseMillis, maxMillis, bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    MusicSearchRetryPolicy(long baseMillis, long maxMillis, LongUnaryOperator random) {
        if (baseMillis <= 0 || maxMillis < baseMillis || maxMillis > Long.MAX_VALUE / 1000) {
            throw new IllegalArgumentException("music search retry settings are invalid");
        }
        this.baseMillis = baseMillis;
        this.maxMillis = maxMillis;
        this.random = random;
    }

    public long delayMillis(int attempt) {
        long cap = baseMillis;
        for (int index = 1; index < attempt && cap < maxMillis; index++) {
            cap = cap > maxMillis / 2 ? maxMillis : Math.min(maxMillis, cap * 2);
        }
        long minimum = cap / 2;
        return minimum + random.applyAsLong(cap - minimum + 1);
    }
}
