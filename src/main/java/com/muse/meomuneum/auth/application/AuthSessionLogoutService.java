package com.muse.meomuneum.auth.application;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;

import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.global.security.TokenClaims;

final class AuthSessionLogoutService {
    private final HazelcastAuthStateStore authStateStore;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;
    private final ApplicationEventPublisher events;
    private final AuthSessionTokenLocator tokenLocator;

    AuthSessionLogoutService(HazelcastAuthStateStore authStateStore,
            RefreshTokenCookieFactory refreshTokenCookieFactory, ApplicationEventPublisher events,
            AuthSessionTokenLocator tokenLocator) {
        this.authStateStore = authStateStore;
        this.refreshTokenCookieFactory = refreshTokenCookieFactory;
        this.events = events;
        this.tokenLocator = tokenLocator;
    }

    void centralizedLogout(HttpServletRequest request, HttpHeaders headers) {
        try (var ignored = authStateStore.acquireRequestPermit()) {
            Optional<TokenClaims> accessLocator = tokenLocator.getAccessTokenLocator(request);
            Optional<RefreshTokenClaims> refreshLocator = tokenLocator.getRefreshTokenLogoutLocator(request);
            String sid = accessLocator.map(TokenClaims::sessionId)
                    .orElseGet(() -> refreshLocator.map(RefreshTokenClaims::sessionId).orElse(null));
            Long userId = accessLocator.map(TokenClaims::userId)
                    .orElseGet(() -> refreshLocator.map(RefreshTokenClaims::userId).orElse(null));
            if (accessLocator.isPresent() && refreshLocator.isPresent()
                    && (!accessLocator.get().sessionId().equals(refreshLocator.get().sessionId())
                            || !accessLocator.get().userId().equals(refreshLocator.get().userId()))) {
                throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
            }
            if (sid != null) {
                var session = authStateStore.revokeSession(sid, userId);
                if (session == null) {
                    throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
                }
                events.publishEvent(new ChatSessionLogoutEvent(session.userId(), session.sid()));
            }
            refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
        }
    }
}
