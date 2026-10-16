package com.muse.meomuneum.location.security;

import java.time.Instant;

public record LocationResolutionClaims(
        Long userId,
        Long sidoRegionId,
        String sidoCode,
        Long sigunguRegionId,
        String sigunguCode,
        Long mapDotId,
        Instant expiresAt,
        String authSessionId) {

    public LocationResolutionClaims(Long userId, Long sidoRegionId, String sidoCode,
            Long sigunguRegionId, String sigunguCode, Long mapDotId, Instant expiresAt) {
        this(userId, sidoRegionId, sidoCode, sigunguRegionId, sigunguCode, mapDotId, expiresAt, null);
    }

    public LocationResolutionClaims(Long userId, Long sidoRegionId, String sidoCode,
            Long sigunguRegionId, String sigunguCode, Instant expiresAt) {
        this(userId, sidoRegionId, sidoCode, sigunguRegionId, sigunguCode, null, expiresAt, null);
    }
}
