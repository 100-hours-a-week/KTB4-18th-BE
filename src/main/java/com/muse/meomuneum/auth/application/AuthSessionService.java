package com.muse.meomuneum.auth.application;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthStateNotFoundException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.chat.connection.ChatSessionLogoutEvent;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.service.UserAuthenticationService;

public final class AuthSessionService {
    private final UserAuthenticationService userAuthenticationService;
    private final HazelcastAuthStateStore authStateStore;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;
    private final ApplicationEventPublisher events;
    private final AuthFlowService authFlowService;
    private final AuthSessionTokenLocator tokenLocator;
    private final AuthSessionRefreshCoordinator refreshCoordinator;
    private final AuthSessionLogoutService logoutService;

    public AuthSessionService(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService, ApplicationEventPublisher events,
            HazelcastAuthStateStore authStateStore, RefreshReplayCipher replayCipher,
            AuthFlowService authFlowService, AuthStateProperties authStateProperties) {
        this.userAuthenticationService = userAuthenticationService;
        this.refreshTokenCookieFactory = refreshTokenCookieFactory;
        this.events = events;
        this.authStateStore = authStateStore;
        this.authFlowService = authFlowService;
        this.tokenLocator = new AuthSessionTokenLocator(jwtTokenProvider);
        this.refreshCoordinator = new AuthSessionRefreshCoordinator(jwtTokenProvider, jwtProperties,
                refreshTokenCookieFactory, userAuthenticationService, authStateStore, replayCipher,
                authFlowService, authStateProperties, tokenLocator);
        this.logoutService = new AuthSessionLogoutService(authStateStore, refreshTokenCookieFactory, events,
                tokenLocator);
    }

    public TokenResponse refresh(HttpServletRequest request, HttpHeaders headers) {
        return refreshCoordinator.centralizedRefresh(request, headers);
    }

    public TokenResponse replayCommittedRefresh(String refreshToken, HttpHeaders headers) {
        return refreshCoordinator.replayCommittedRefresh(refreshToken, headers);
    }

    public void revokeCurrentSession(HttpServletRequest request, HttpHeaders headers, Long expectedUserId) {
        if (authFlowService != null) {
            Optional<TokenClaims> accessLocator = tokenLocator.getAccessTokenLocator(request);
            if (accessLocator.isEmpty() || accessLocator.get().sessionId() == null
                    || !expectedUserId.equals(accessLocator.get().userId())) {
                throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
            }
            refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
            publishSessionRevocation(expectedUserId, accessLocator.get().sessionId());
            return;
        }
        try (var ignored = authStateStore.acquireRequestPermit()) {
            Optional<TokenClaims> accessLocator = tokenLocator.getAccessTokenLocator(request);
            if (accessLocator.isEmpty() || accessLocator.get().sessionId() == null
                    || !expectedUserId.equals(accessLocator.get().userId())) {
                throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
            }
            var revoked = authStateStore.revokeSession(accessLocator.get().sessionId(), expectedUserId);
            if (revoked == null) {
                throw new AuthStateUnavailableException();
            }
            refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
        }
    }

    public java.util.List<HazelcastAuthStateStore.AuthSession> listSessions(Long userId) {
        try (var ignored = authStateStore.acquireRequestPermit()) {
            return authStateStore.activeSessions(userId);
        }
    }

    public boolean revokeSession(Long userId, String sid) {
        try (var ignored = authStateStore.acquireRequestPermit()) {
            HazelcastAuthStateStore.AuthSession session = authStateStore.getSession(sid);
            if (session == null) {
                throw new AuthStateNotFoundException("SESSION_NOT_FOUND");
            }
            if (!userId.equals(session.userId())) {
                throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
            }
            if (authStateStore.revokeSession(sid, userId) == null) {
                throw new AuthStateUnavailableException();
            }
            return true;
        }
    }

    public void logout(HttpServletRequest request, HttpHeaders headers) {
        if (authFlowService != null) {
            refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
            return;
        }
        logoutService.centralizedLogout(request, headers);
    }

    public void publishSessionRevocation(Long userId, String sid) {
        if (userId != null && sid != null) {
            events.publishEvent(new ChatSessionLogoutEvent(userId, sid));
        }
    }
}
