package com.muse.meomuneum.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(String issuer, String audience, String secret, long accessTokenExpirationSeconds,
        long refreshTokenExpirationSeconds) {
}
