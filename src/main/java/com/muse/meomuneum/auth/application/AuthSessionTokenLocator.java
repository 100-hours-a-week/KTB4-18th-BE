package com.muse.meomuneum.auth.application;

import java.util.Arrays;
import java.util.Optional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.global.security.TokenClaims;

final class AuthSessionTokenLocator {
    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private static final Logger log = LoggerFactory.getLogger(AuthSessionTokenLocator.class);

    private final JwtTokenProvider jwtTokenProvider;

    AuthSessionTokenLocator(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    String getRefreshToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            throw invalidRefreshToken("refresh_cookie_missing");
        }
        String refreshToken = Arrays.stream(request.getCookies())
                .filter(cookie -> REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElseThrow(() -> invalidRefreshToken("refresh_cookie_missing"));
        if (refreshToken.isBlank()) {
            throw invalidRefreshToken("refresh_cookie_empty");
        }
        return refreshToken;
    }

    RefreshTokenClaims parseRefreshToken(String refreshToken) {
        try {
            return jwtTokenProvider.parseRefreshToken(refreshToken);
        } catch (AuthenticationFailedException exception) {
            logRefreshFailure("refresh_token_invalid");
            throw exception;
        }
    }

    Optional<String> getOptionalCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies()).filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue).filter(value -> !value.isBlank()).findFirst();
    }

    Optional<TokenClaims> getAccessTokenLocator(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return Optional.empty();
        }
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
        }
        try {
            return Optional.of(jwtTokenProvider.parseAccessTokenForLogout(authorization.substring(7)));
        } catch (AuthenticationFailedException exception) {
            throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
        }
    }

    Optional<RefreshTokenClaims> getRefreshTokenLogoutLocator(HttpServletRequest request) {
        return getOptionalCookie(request, REFRESH_TOKEN_COOKIE_NAME).map(token -> {
            try {
                return jwtTokenProvider.parseRefreshTokenForLogout(token);
            } catch (AuthenticationFailedException exception) {
                throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
            }
        });
    }

    AuthenticationFailedException invalidRefreshToken(String reason) {
        logRefreshFailure(reason);
        return new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN);
    }

    void logRefreshFailure(String reason) {
        log.warn("event=auth_refresh_rejected reason={} requestId={}", reason, MDC.get("requestId"));
    }
}
