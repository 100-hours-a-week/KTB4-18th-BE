package com.muse.meomuneum.global.security;

import java.time.Instant;
import java.util.List;

public record TokenClaims(Long userId, List<String> roles, Instant expiresAt) {

    public TokenClaims(Long userId, List<String> roles) {
        this(userId, roles, null);
    }
}
