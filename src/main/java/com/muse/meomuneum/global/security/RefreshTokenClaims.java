package com.muse.meomuneum.global.security;

import java.time.Instant;

public record RefreshTokenClaims(Long userId, Instant expiresAt) {
}
