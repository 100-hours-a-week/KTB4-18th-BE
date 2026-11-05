package com.muse.meomuneum.auth.directv2.model;

import java.security.MessageDigest;
import java.time.LocalDateTime;

public record DirectAuthOperationState(String flowId, String operationId, long startingEpoch, String status,
        Long proofGeneration, byte[] ownerToken, LocalDateTime leaseExpiresAt, Long resultEpoch, Long resultRevision,
        String resultSid, LocalDateTime receiptExpiresAt, Long clientExpiresAt, Long effectiveExpiresAt,
        String reasonCode, byte[] originalProof) {
    public boolean ownedBy(byte[] owner, long epoch, long generation, LocalDateTime databaseTime) {
        return databaseTime != null && owner != null && ownerToken != null && MessageDigest.isEqual(owner, ownerToken)
                && "IN_PROGRESS".equals(status) && startingEpoch == epoch && proofGeneration != null
                && proofGeneration == generation && leaseExpiresAt != null && leaseExpiresAt.isAfter(databaseTime)
                && receiptExpiresAt != null && receiptExpiresAt.isAfter(databaseTime)
                && clientExpiresAt != null && effectiveExpiresAt != null
                && clientExpiresAt > 0 && clientExpiresAt <= 9_007_199_254_740_991L
                && effectiveExpiresAt > 0 && effectiveExpiresAt <= clientExpiresAt
                && databaseTime.isBefore(java.time.Instant.ofEpochMilli(effectiveExpiresAt)
                        .atOffset(java.time.ZoneOffset.UTC).toLocalDateTime());
    }
}
