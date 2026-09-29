package com.muse.meomuneum.global.security;

import java.time.Instant;
import java.util.List;

public record TokenClaims(Long userId, List<String> roles, Instant expiresAt, String sessionId, String tokenId) {

    public TokenClaims(Long userId, List<String> roles, Instant expiresAt) {
        this(userId, roles, expiresAt, null, null);
    }

    public TokenClaims(Long userId, List<String> roles) {
        this(userId, roles, null, null, null);
    }
}
