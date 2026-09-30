package com.muse.meomuneum.auth.service;

import java.time.Instant;

import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.global.security.TokenClaims;

/** Keeps the stable refresh token's signed identity bound to the servlet session. */
@Service
public class RefreshTokenSessionService {

    static final String USER_ID_ATTRIBUTE = "auth.refresh.user-id";
    static final String SESSION_ID_ATTRIBUTE = "auth.refresh.session-id";
    static final String TOKEN_ID_ATTRIBUTE = "auth.refresh.token-id";
    static final String EXPIRES_AT_ATTRIBUTE = "auth.refresh.expires-at";

    public void register(HttpSession session, RefreshTokenClaims claims) {
        synchronized (session) {
            if (claims.sessionId() == null || claims.tokenId() == null
                    || !claims.sessionId().equals(session.getId()) || claims.expiresAt() == null) {
                throw invalidRefreshToken();
            }
            session.setAttribute(USER_ID_ATTRIBUTE, claims.userId());
            session.setAttribute(SESSION_ID_ATTRIBUTE, claims.sessionId());
            session.setAttribute(TOKEN_ID_ATTRIBUTE, claims.tokenId());
            session.setAttribute(EXPIRES_AT_ATTRIBUTE, claims.expiresAt());
            long remainingSeconds = java.time.Duration.between(Instant.now(), claims.expiresAt()).toSeconds();
            if (remainingSeconds <= 0) {
                throw invalidRefreshToken();
            }
            session.setMaxInactiveInterval((int) Math.min(remainingSeconds, Integer.MAX_VALUE));
        }
    }

    public void validate(HttpSession session, RefreshTokenClaims claims) {
        synchronized (session) {
            if (claims.expiresAt() == null || !claims.expiresAt().isAfter(Instant.now())
                    || !claims.userId().equals(session.getAttribute(USER_ID_ATTRIBUTE))
                    || !claims.sessionId().equals(session.getId())
                    || !claims.sessionId().equals(session.getAttribute(SESSION_ID_ATTRIBUTE))
                    || !claims.tokenId().equals(session.getAttribute(TOKEN_ID_ATTRIBUTE))
                    || !claims.expiresAt().equals(session.getAttribute(EXPIRES_AT_ATTRIBUTE))) {
                throw invalidRefreshToken();
            }
        }
    }

    public boolean matchesAccessLocator(HttpSession session, TokenClaims claims) {
        synchronized (session) {
            return claims.sessionId() != null
                    && claims.userId().equals(session.getAttribute(USER_ID_ATTRIBUTE))
                    && claims.sessionId().equals(session.getId())
                    && claims.sessionId().equals(session.getAttribute(SESSION_ID_ATTRIBUTE));
        }
    }

    public boolean matchesRefreshLocator(HttpSession session, RefreshTokenClaims claims) {
        synchronized (session) {
            return claims.sessionId() != null
                    && claims.userId().equals(session.getAttribute(USER_ID_ATTRIBUTE))
                    && claims.sessionId().equals(session.getId())
                    && claims.sessionId().equals(session.getAttribute(SESSION_ID_ATTRIBUTE))
                    && claims.tokenId().equals(session.getAttribute(TOKEN_ID_ATTRIBUTE));
        }
    }

    public void clear(HttpSession session) {
        synchronized (session) {
            session.removeAttribute(USER_ID_ATTRIBUTE);
            session.removeAttribute(SESSION_ID_ATTRIBUTE);
            session.removeAttribute(TOKEN_ID_ATTRIBUTE);
            session.removeAttribute(EXPIRES_AT_ATTRIBUTE);
        }
    }

    private AuthenticationFailedException invalidRefreshToken() {
        return new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN);
    }
}
