package com.muse.meomuneum.global.security;

import java.time.Instant;

public record RefreshTokenClaims(Long userId, Instant expiresAt, String sessionId, String tokenId) {

    public RefreshTokenClaims(Long userId, Instant expiresAt) {
        this(userId, expiresAt, null, null);
    }
}
