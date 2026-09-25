package com.muse.meomuneum.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.security.RefreshTokenClaims;

@Service
public class RefreshTokenSessionService {

    static final String USER_ID_ATTRIBUTE = "auth.refresh.user-id";
    static final String ACTIVE_TOKEN_HASH_ATTRIBUTE = "auth.refresh.active-token-hash";
    static final String CONSUMED_TOKEN_HASH_ATTRIBUTE = "auth.refresh.consumed-token-hash";
    static final String EXPIRES_AT_ATTRIBUTE = "auth.refresh.expires-at";

    public void register(HttpServletRequest request, RefreshTokenClaims claims, String refreshToken) {
        HttpSession session = request.getSession(true);
        synchronized (session) {
            setSessionAttributes(session, claims.userId(), hash(refreshToken), claims.expiresAt());
            session.removeAttribute(CONSUMED_TOKEN_HASH_ATTRIBUTE);
        }
    }

    public void rotate(HttpServletRequest request, RefreshTokenClaims currentClaims, String currentRefreshToken,
            RefreshTokenClaims nextClaims, String nextRefreshToken) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw invalidRefreshToken();
        }

        synchronized (session) {
            try {
                validateActiveSession(session, currentClaims, currentRefreshToken);
                String currentTokenHash = hash(currentRefreshToken);
                setSessionAttributes(session, nextClaims.userId(), hash(nextRefreshToken), nextClaims.expiresAt());
                session.setAttribute(CONSUMED_TOKEN_HASH_ATTRIBUTE, currentTokenHash);
            } catch (AuthenticationFailedException exception) {
                invalidate(session);
                throw exception;
            } catch (IllegalStateException exception) {
                throw invalidRefreshToken();
            }
        }
    }

    private void validateActiveSession(HttpSession session, RefreshTokenClaims claims, String refreshToken) {
        if (!claims.userId().equals(session.getAttribute(USER_ID_ATTRIBUTE))) {
            throw invalidRefreshToken();
        }

        String refreshTokenHash = hash(refreshToken);
        Object consumedTokenHash = session.getAttribute(CONSUMED_TOKEN_HASH_ATTRIBUTE);
        if (refreshTokenHash.equals(consumedTokenHash)) {
            throw invalidRefreshToken();
        }

        Object activeTokenHash = session.getAttribute(ACTIVE_TOKEN_HASH_ATTRIBUTE);
        if (!(activeTokenHash instanceof String) || !refreshTokenHash.equals(activeTokenHash)) {
            throw invalidRefreshToken();
        }
    }

    private void setSessionAttributes(HttpSession session, Long userId, String refreshTokenHash, Instant expiresAt) {
        int maxInactiveInterval = getMaxInactiveInterval(expiresAt);
        session.setAttribute(USER_ID_ATTRIBUTE, userId);
        session.setAttribute(ACTIVE_TOKEN_HASH_ATTRIBUTE, refreshTokenHash);
        session.setAttribute(EXPIRES_AT_ATTRIBUTE, expiresAt);
        session.setMaxInactiveInterval(maxInactiveInterval);
    }

    private int getMaxInactiveInterval(Instant expiresAt) {
        long remainingSeconds = Duration.between(Instant.now(), expiresAt).toSeconds();
        if (remainingSeconds <= 0) {
            throw invalidRefreshToken();
        }

        return (int) Math.min(remainingSeconds, Integer.MAX_VALUE);
    }

    private String hash(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private void invalidate(HttpSession session) {
        session.invalidate();
    }

    private AuthenticationFailedException invalidRefreshToken() {
        return new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN);
    }
}
