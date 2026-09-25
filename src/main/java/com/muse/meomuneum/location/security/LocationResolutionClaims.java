package com.muse.meomuneum.location.security;

import java.time.Instant;

public record LocationResolutionClaims(
        Long userId,
        Long sidoRegionId,
        String sidoCode,
        Long sigunguRegionId,
        String sigunguCode,
        Instant expiresAt) {
}
