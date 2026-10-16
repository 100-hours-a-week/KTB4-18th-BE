package com.muse.meomuneum.auth.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthStateConflictException;
import com.muse.meomuneum.auth.exception.AuthStateNotFoundException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;

public final class AuthSessionService {
    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private static final Logger log = LoggerFactory.getLogger(AuthSessionService.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;
    private final UserAuthenticationService userAuthenticationService;
    private final ApplicationEventPublisher events;
    private final HazelcastAuthStateStore authStateStore;
    private final RefreshReplayCipher replayCipher;
    private final AuthStateProperties authStateProperties;

    public AuthSessionService(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService, ApplicationEventPublisher events,
            HazelcastAuthStateStore authStateStore, RefreshReplayCipher replayCipher,
            AuthStateProperties authStateProperties) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenCookieFactory = refreshTokenCookieFactory;
        this.userAuthenticationService = userAuthenticationService;
        this.events = events;
        this.authStateStore = authStateStore;
        this.replayCipher = replayCipher;
        this.authStateProperties = authStateProperties;
    }

    public TokenResponse refresh(HttpServletRequest request, HttpHeaders headers) {
        return centralizedRefresh(request, headers);
    }

    public void revokeCurrentSession(HttpServletRequest request, HttpHeaders headers, Long expectedUserId) {
        try (var ignored = authStateStore.acquireRequestPermit()) {
            Optional<TokenClaims> accessLocator = getAccessTokenLocator(request);
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
        centralizedLogout(request, headers);
    }

    private TokenResponse centralizedRefresh(HttpServletRequest request, HttpHeaders headers) {
        try (var ignored = authStateStore.acquireRequestPermit()) {
            String operationId = request.getHeader("X-Refresh-Operation-Id");
            if (operationId == null) {
                throw new AuthStateConflictException("INVALID_OPERATION_ID");
            }
            try {
                UUID.fromString(operationId);
            } catch (IllegalArgumentException exception) {
                throw new AuthStateConflictException("INVALID_OPERATION_ID");
            }
            String presentedToken = getRefreshToken(request);
            RefreshTokenClaims claims = parseRefreshToken(presentedToken);
            var presentedHash = authStateStore.tokenHash(presentedToken);
            var presentedJtiHash = authStateStore.jtiHash(claims.tokenId());
            for (int attempt = 0; attempt < 5; attempt++) {
                HazelcastAuthStateStore.AuthSession session = authStateStore.getSession(claims.sessionId());
                if (session == null || session.status() != HazelcastAuthStateStore.SessionStatus.ACTIVE
                        || !session.userId().equals(claims.userId())) {
                    throw invalidRefreshToken("refresh_session_missing");
                }
                Instant now = Instant.now();
                if (!session.refreshIdleExpiresAt().isAfter(now)
                        || !session.refreshAbsoluteExpiresAt().isAfter(now)) {
                    authStateStore.revokeSession(session.sid(), session.userId());
                    throw invalidRefreshToken("refresh_session_expired");
                }

                boolean currentToken = session.currentJtiHash().equals(presentedJtiHash)
                        && session.currentRefreshHash().equals(presentedHash);
                boolean oldToken = presentedJtiHash.equals(session.previousJtiHash())
                        && presentedHash.equals(session.previousRefreshHash());
                if (oldToken && operationId.equals(session.lastRefreshOperationId())
                        && session.replayUntil() != null && session.replayUntil().isAfter(now)
                        && session.encryptedReplayRefreshToken() != null) {
                    return replayRefreshResult(session, user(session.userId()), operationId,
                            session.encryptedReplayRefreshToken(), headers);
                }
                if (currentToken && operationId.equals(session.lastRefreshOperationId())
                        && session.replayUntil() != null && session.replayUntil().isAfter(now)
                        && session.encryptedReplayRefreshToken() != null) {
                    return replayRefreshResult(session, user(session.userId()), operationId,
                            session.encryptedReplayRefreshToken(), headers);
                }
                if (!currentToken) {
                    authStateStore.revokeSession(session.sid(), session.userId());
                    throw invalidRefreshToken("refresh_token_reused");
                }

                User activeUser = user(session.userId());
                String nextRefreshToken = jwtTokenProvider.createRefreshToken(activeUser, session.sid());
                RefreshTokenClaims nextClaims = jwtTokenProvider.parseRefreshToken(nextRefreshToken);
                String nextAccessToken = jwtTokenProvider.createAccessToken(activeUser, session.sid());
                TokenClaims nextAccessClaims = jwtTokenProvider.parseAccessToken(nextAccessToken);
                Instant nextIdleExpiry = authStateStore.nextRefreshIdleExpiry(session.refreshAbsoluteExpiresAt());
                var updated = session.withRotation(authStateStore.jtiHash(nextClaims.tokenId()),
                        authStateStore.tokenHash(nextRefreshToken),
                        presentedJtiHash, presentedHash, operationId,
                        now.plusSeconds(authStateProperties.refreshReplayWindowSeconds()),
                        replayCipher.encrypt(nextRefreshToken), nextAccessClaims.expiresAt(), nextIdleExpiry);
                if (authStateStore.replaceSession(session, updated)) {
                    refreshTokenCookieFactory.addRefreshTokenCookie(headers, nextRefreshToken);
                    return new TokenResponse(nextAccessToken, jwtProperties.accessTokenExpirationSeconds());
                }
            }
            throw new AuthStateUnavailableException();
        }
    }

    private TokenResponse replayRefreshResult(HazelcastAuthStateStore.AuthSession session, User user,
            String operationId, String encryptedRefresh, HttpHeaders headers) {
        String refreshToken = replayCipher.decrypt(encryptedRefresh);
        String accessToken = jwtTokenProvider.createAccessToken(user, session.sid());
        refreshTokenCookieFactory.addRefreshTokenCookie(headers, refreshToken);
        return new TokenResponse(accessToken, jwtProperties.accessTokenExpirationSeconds());
    }

    private User user(Long userId) {
        try {
            return userAuthenticationService.findActiveUser(userId);
        } catch (AuthenticationFailedException exception) {
            logRefreshFailure("user_unavailable");
            throw exception;
        }
    }

    private void centralizedLogout(HttpServletRequest request, HttpHeaders headers) {
        try (var ignored = authStateStore.acquireRequestPermit()) {
            Optional<TokenClaims> accessLocator = getAccessTokenLocator(request);
            Optional<RefreshTokenClaims> refreshLocator = getRefreshTokenLogoutLocator(request);
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
                events.publishEvent(new ChatLogoutEvent(session.userId()));
            }
            refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
        }
    }

    private String getRefreshToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            throw invalidRefreshToken("refresh_cookie_missing");
        }

        String refreshToken = Arrays.stream(request.getCookies())
                .filter(cookie -> REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName()))
                .map(cookie -> cookie.getValue())
                .findFirst()
                .orElseThrow(() -> invalidRefreshToken("refresh_cookie_missing"));
        if (refreshToken.isBlank()) {
            throw invalidRefreshToken("refresh_cookie_empty");
        }
        return refreshToken;
    }

    private RefreshTokenClaims parseRefreshToken(String refreshToken) {
        try {
            return jwtTokenProvider.parseRefreshToken(refreshToken);
        } catch (AuthenticationFailedException exception) {
            logRefreshFailure("refresh_token_invalid");
            throw exception;
        }
    }

    private Optional<String> getOptionalCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies()).filter(cookie -> name.equals(cookie.getName()))
                .map(cookie -> cookie.getValue()).filter(value -> !value.isBlank()).findFirst();
    }

    private Optional<TokenClaims> getAccessTokenLocator(HttpServletRequest request) {
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

    private Optional<RefreshTokenClaims> getRefreshTokenLogoutLocator(HttpServletRequest request) {
        return getOptionalCookie(request, REFRESH_TOKEN_COOKIE_NAME).map(token -> {
            try {
                return jwtTokenProvider.parseRefreshTokenForLogout(token);
            } catch (AuthenticationFailedException exception) {
                throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
            }
        });
    }

    private AuthenticationFailedException invalidRefreshToken(String reason) {
        logRefreshFailure(reason);
        return new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN);
    }

    private void logRefreshFailure(String reason) {
        log.warn("event=auth_refresh_rejected reason={} requestId={}", reason, MDC.get("requestId"));
    }
}
