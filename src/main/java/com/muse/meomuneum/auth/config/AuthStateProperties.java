package com.muse.meomuneum.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "auth.state")
public record AuthStateProperties(
        String hmacKey,
        String replayEncryptionKey,
        String queueCookieName,
        long sessionIdleSeconds,
        long sessionAbsoluteSeconds,
        long queueTicketSeconds,
        long readyTicketSeconds,
        long queueTombstoneSeconds,
        int queueCapacity,
        int maxDeviceSessions,
        int localAuthConcurrency,
        int localBcryptConcurrency,
        int globalBcryptPermits,
        long bcryptPermitLeaseSeconds,
        long queueCookieSeconds,
        long statusMinimumIntervalSeconds,
        int queueCreateRequestsPerMinute,
        int operationRecordCapacity,
        long idempotencyIdleHorizonSeconds,
        int expectedHazelcastMembers,
        int globalSessionCapacity,
        long revokedSessionRetentionSeconds,
        long refreshReplayWindowSeconds,
        int maxActiveTickets) {

    public AuthStateProperties(String hmacKey, String replayEncryptionKey, String queueCookieName,
            long sessionIdleSeconds, long sessionAbsoluteSeconds, long queueTicketSeconds, long readyTicketSeconds,
            long queueTombstoneSeconds, int queueCapacity, int maxDeviceSessions, int localAuthConcurrency,
            int localBcryptConcurrency, int globalBcryptPermits, long bcryptPermitLeaseSeconds,
            long queueCookieSeconds, long statusMinimumIntervalSeconds, int queueCreateRequestsPerMinute,
            int operationRecordCapacity, long idempotencyIdleHorizonSeconds, int expectedHazelcastMembers,
            int globalSessionCapacity, long revokedSessionRetentionSeconds, long refreshReplayWindowSeconds) {
        this(hmacKey, replayEncryptionKey, queueCookieName, sessionIdleSeconds, sessionAbsoluteSeconds,
                queueTicketSeconds, readyTicketSeconds, queueTombstoneSeconds, queueCapacity, maxDeviceSessions,
                localAuthConcurrency, localBcryptConcurrency, globalBcryptPermits, bcryptPermitLeaseSeconds,
                queueCookieSeconds, statusMinimumIntervalSeconds, queueCreateRequestsPerMinute,
                operationRecordCapacity, idempotencyIdleHorizonSeconds, expectedHazelcastMembers, globalSessionCapacity,
                revokedSessionRetentionSeconds, refreshReplayWindowSeconds, 150);
    }

    @ConstructorBinding
    public AuthStateProperties {
        queueCookieName = queueCookieName == null || queueCookieName.isBlank() ? "MEO_QUEUE_V2" : queueCookieName;
        sessionIdleSeconds = positive(sessionIdleSeconds, 1_209_600);
        sessionAbsoluteSeconds = positive(sessionAbsoluteSeconds, 7_776_000);
        queueTicketSeconds = positive(queueTicketSeconds, 900);
        readyTicketSeconds = positive(readyTicketSeconds, 60);
        queueTombstoneSeconds = positive(queueTombstoneSeconds, 900);
        queueCapacity = positive(queueCapacity, 2_000);
        maxActiveTickets = positive(maxActiveTickets, 150);
        maxDeviceSessions = positive(maxDeviceSessions, 10);
        localAuthConcurrency = positive(localAuthConcurrency, 64);
        localBcryptConcurrency = positive(localBcryptConcurrency, 150);
        globalBcryptPermits = positive(globalBcryptPermits, 20);
        bcryptPermitLeaseSeconds = positive(bcryptPermitLeaseSeconds, 120);
        if (bcryptPermitLeaseSeconds <= readyTicketSeconds) {
            throw new IllegalArgumentException("bcrypt permit lease must exceed the READY ticket deadline");
        }
        queueCookieSeconds = positive(queueCookieSeconds, 1_800);
        statusMinimumIntervalSeconds = positive(statusMinimumIntervalSeconds, 5);
        queueCreateRequestsPerMinute = positive(queueCreateRequestsPerMinute, 2000);
        operationRecordCapacity = positive(operationRecordCapacity, queueCapacity + localBcryptConcurrency + 1024);
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
