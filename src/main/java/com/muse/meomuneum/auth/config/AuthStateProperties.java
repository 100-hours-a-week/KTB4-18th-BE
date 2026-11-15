package com.muse.meomuneum.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "auth.state")
public record AuthStateProperties(
        String hmacKey,
        String replayEncryptionKey,
        long sessionIdleSeconds,
        long sessionAbsoluteSeconds,
        int maxDeviceSessions,
        int localAuthConcurrency,
        int localBcryptConcurrency,
        long idempotencyIdleHorizonSeconds,
        int expectedHazelcastMembers,
        int globalSessionCapacity,
        long revokedSessionRetentionSeconds,
        long refreshReplayWindowSeconds) {

    @ConstructorBinding
    public AuthStateProperties {
        sessionIdleSeconds = positive(sessionIdleSeconds, 1_209_600);
        sessionAbsoluteSeconds = positive(sessionAbsoluteSeconds, 7_776_000);
        maxDeviceSessions = positive(maxDeviceSessions, 10);
        localAuthConcurrency = positive(localAuthConcurrency, 64);
        localBcryptConcurrency = positive(localBcryptConcurrency, 150);
        idempotencyIdleHorizonSeconds = positive(idempotencyIdleHorizonSeconds, 86_400);
        expectedHazelcastMembers = positive(expectedHazelcastMembers, 1);
        globalSessionCapacity = positive(globalSessionCapacity, 100_000);
        revokedSessionRetentionSeconds = positive(revokedSessionRetentionSeconds, 300);
        refreshReplayWindowSeconds = positive(refreshReplayWindowSeconds, 60);
    }

    public int minimumHazelcastMembers() {
        return expectedHazelcastMembers / 2 + 1;
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }
}
