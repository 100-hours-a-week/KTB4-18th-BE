package com.muse.meomuneum.auth.directv2.model;

import java.time.LocalDateTime;

public record DirectAuthFlowState(String flowId, String flowKeyHash, long epoch, long revision, String lifecycle,
        String activeSessionSid, long flowCookieGeneration, long bindingCookieGeneration, byte[] bindingProofHmac,
        LocalDateTime bindingCookieExpiresAt, String activeLoginOperationId, String currentOperationKind,
        String currentOperationId, String recoveryOperationId, String recoverySourceOperationId,
        String recoveryStatus, LocalDateTime recoveryLeaseExpiresAt, Long recoveryClaimFlowCookieGeneration,
        Long recoveryClaimRefreshGeneration, Long recoveryResultFlowCookieGeneration,
        Long recoveryResultRefreshGeneration) {
}
