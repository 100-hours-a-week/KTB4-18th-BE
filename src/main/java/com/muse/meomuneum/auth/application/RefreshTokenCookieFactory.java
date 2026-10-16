package com.muse.meomuneum.auth.application;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.global.config.JwtProperties;

@Component
public class RefreshTokenCookieFactory {

    private static final String AUTH_COOKIE_PATH = "/api/v1/auth";
    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";

    private final JwtProperties jwtProperties;
    private final boolean secure;
    private final String sameSite;

    @Autowired
    public RefreshTokenCookieFactory(JwtProperties jwtProperties,
            @Value("${auth.cookie.secure:true}") boolean secure,
            @Value("${auth.cookie.same-site:Lax}") String sameSite) {
        this.jwtProperties = jwtProperties;
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public RefreshTokenCookieFactory(JwtProperties jwtProperties, boolean secure) {
        this(jwtProperties, secure, "Lax");
    }

    public void addRefreshTokenCookie(HttpHeaders headers, String refreshToken) {
        ResponseCookie refreshTokenCookie = buildCookie(refreshToken, jwtProperties.refreshTokenExpirationSeconds());
        headers.add(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString());
    }

    public void deleteRefreshTokenCookie(HttpHeaders headers) {
        headers.add(HttpHeaders.SET_COOKIE, buildCookie("", 0).toString());
    }

    private ResponseCookie buildCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, value).httpOnly(true).secure(secure).sameSite(sameSite)
                .path(AUTH_COOKIE_PATH).maxAge(Duration.ofSeconds(maxAgeSeconds)).build();
    }
}
