package com.muse.meomuneum.auth.service;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.global.config.JwtProperties;

@Component
public class RefreshTokenCookieFactory {

    private static final String AUTH_COOKIE_PATH = "/api/v1/auth";
    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";

    private final JwtProperties jwtProperties;

    public RefreshTokenCookieFactory(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    public void addRefreshTokenCookie(HttpHeaders headers, String refreshToken) {
        headers.add(HttpHeaders.SET_COOKIE, buildCookie(refreshToken, jwtProperties.refreshTokenExpirationSeconds()).toString());
    }

    public void deleteRefreshTokenCookie(HttpHeaders headers) {
        headers.add(HttpHeaders.SET_COOKIE, buildCookie("", 0).toString());
    }

    private ResponseCookie buildCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(AUTH_COOKIE_PATH)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }
}
