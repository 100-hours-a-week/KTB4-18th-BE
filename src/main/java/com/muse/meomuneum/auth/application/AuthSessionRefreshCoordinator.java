package com.muse.meomuneum.auth.application;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.exception.AuthStateConflictException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;

final class AuthSessionRefreshCoordinator {
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;
    private final UserAuthenticationService userAuthenticationService;
    private final HazelcastAuthStateStore authStateStore;
    private final RefreshReplayCipher replayCipher;
    private final AuthFlowService authFlowService;
    private final AuthStateProperties authStateProperties;
    private final AuthSessionTokenLocator tokenLocator;

    AuthSessionRefreshCoordinator(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService, HazelcastAuthStateStore authStateStore,
            RefreshReplayCipher replayCipher, AuthFlowService authFlowService,
            AuthStateProperties authStateProperties, AuthSessionTokenLocator tokenLocator) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenCookieFactory = refreshTokenCookieFactory;
        this.userAuthenticationService = userAuthenticationService;
        this.authStateStore = authStateStore;
        this.replayCipher = replayCipher;
        this.authFlowService = authFlowService;
        this.authStateProperties = authStateProperties;
        this.tokenLocator = tokenLocator;
    }

    TokenResponse replayCommittedRefresh(String refreshToken, HttpHeaders headers) {
        RefreshTokenClaims claims = tokenLocator.parseRefreshToken(refreshToken);
        User activeUser = user(claims.userId());
        String accessToken = jwtTokenProvider.createAccessToken(activeUser, claims.sessionId());
        refreshTokenCookieFactory.addRefreshTokenCookie(headers, refreshToken);
        return new TokenResponse(accessToken, jwtProperties.accessTokenExpirationSeconds());
    }

    TokenResponse centralizedRefresh(HttpServletRequest request, HttpHeaders headers) {
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
            String presentedToken = tokenLocator.getRefreshToken(request);
            RefreshTokenClaims claims = tokenLocator.parseRefreshToken(presentedToken);
            var presentedHash = authStateStore.tokenHash(presentedToken);
            var presentedJtiHash = authStateStore.jtiHash(claims.tokenId());
            if (authFlowService != null) {
                authFlowService.assertActiveSession(claims.userId(), claims.sessionId());
            }
            for (int attempt = 0; attempt < 5; attempt++) {
                HazelcastAuthStateStore.AuthSession session = authStateStore.getSession(claims.sessionId());
                if (session == null && authFlowService != null) {
                    session = restoreCanonicalSession(claims, presentedToken);
                } else if (session != null && authFlowService != null) {
                    session = reconcileCanonicalSession(session, claims, presentedToken);
                    if (session == null) {
                        continue;
                    }
                }
                if (session == null || session.status() != HazelcastAuthStateStore.SessionStatus.ACTIVE
                        || !session.userId().equals(claims.userId())) {
                    if (authFlowService != null) {
                        throw new AuthStateUnavailableException();
                    }
                    throw tokenLocator.invalidRefreshToken("refresh_session_missing");
                }
                Instant now = Instant.now();
                if (!session.refreshIdleExpiresAt().isAfter(now)
                        || !session.refreshAbsoluteExpiresAt().isAfter(now)) {
                    if (authFlowService != null) {
                        throw new AuthStateUnavailableException();
                    }
                    authStateStore.revokeSession(session.sid(), session.userId());
                    throw tokenLocator.invalidRefreshToken("refresh_session_expired");
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
                if (!currentToken && authFlowService != null
                        && authFlowService.isConsumedRefreshToken(session.sid(), presentedHash)) {
                    throw new AuthStateConflictException("AUTH_REFRESH_STALE");
                }
                if (!currentToken) {
                    if (authFlowService != null) {
                        throw new AuthStateUnavailableException();
                    }
                    authStateStore.revokeSession(session.sid(), session.userId());
                    throw tokenLocator.invalidRefreshToken("refresh_token_reused");
                }

                User activeUser = user(session.userId());
                String nextRefreshToken = jwtTokenProvider.createRefreshToken(activeUser, session.sid());
                RefreshTokenClaims nextClaims = jwtTokenProvider.parseRefreshToken(nextRefreshToken);
                String nextAccessToken = jwtTokenProvider.createAccessToken(activeUser, session.sid());
                var nextAccessClaims = jwtTokenProvider.parseAccessToken(nextAccessToken);
                Instant nextIdleExpiry = authStateStore.nextRefreshIdleExpiry(session.refreshAbsoluteExpiresAt());
                var updated = session.withRotation(authStateStore.jtiHash(nextClaims.tokenId()),
                        authStateStore.tokenHash(nextRefreshToken), presentedJtiHash, presentedHash, operationId,
                        now.plusSeconds(authStateProperties.refreshReplayWindowSeconds()),
                        replayCipher.encrypt(nextRefreshToken), nextAccessClaims.expiresAt(), nextIdleExpiry);
                if (authStateStore.replaceSession(session, updated)) {
                    if (authFlowService != null) {
                        authFlowService.recordConsumedRefreshToken(session.sid(), presentedHash,
                                session.refreshAbsoluteExpiresAt());
                    }
                    refreshTokenCookieFactory.addRefreshTokenCookie(headers, nextRefreshToken);
                    return new TokenResponse(nextAccessToken, jwtProperties.accessTokenExpirationSeconds());
                }
            }
            throw new AuthStateUnavailableException();
        }
    }

    private HazelcastAuthStateStore.AuthSession restoreCanonicalSession(RefreshTokenClaims presentedClaims,
            String presentedToken) {
        var projection = authFlowService.currentRefreshSession(presentedClaims.sessionId(),
                presentedClaims.userId(), replayCipher);
        if (projection == null || !projection.sid().equals(presentedClaims.sessionId())
                || !projection.userId().equals(presentedClaims.userId())) {
            throw new AuthStateUnavailableException();
        }
        RefreshTokenClaims canonicalClaims = tokenLocator.parseRefreshToken(projection.refreshToken());
        if (!canonicalClaims.sessionId().equals(presentedClaims.sessionId())
                || !canonicalClaims.userId().equals(presentedClaims.userId())
                || !authStateStore.tokenHash(projection.refreshToken()).equals(
                        authStateStore.tokenHash(presentedToken))) {
            throw new AuthStateConflictException("AUTH_REFRESH_STALE");
        }
        User activeUser = user(projection.userId());
        Instant accessExpiry = Instant.now().plusSeconds(jwtProperties.accessTokenExpirationSeconds());
        HazelcastAuthStateStore.AuthSession restored = authStateStore.createSession(projection.userId(),
                projection.sid(), canonicalClaims.tokenId(), authStateStore.tokenHash(projection.refreshToken()),
                null, replayCipher.encrypt(projection.refreshToken()), accessExpiry,
                projection.absoluteExpiry(), "Restored browser session");
        if (restored == null || restored.status() != HazelcastAuthStateStore.SessionStatus.ACTIVE
                || !restored.userId().equals(projection.userId())
                || !restored.currentRefreshHash().equals(authStateStore.tokenHash(projection.refreshToken()))) {
            throw new AuthStateUnavailableException();
        }
        return restored;
    }

    private HazelcastAuthStateStore.AuthSession reconcileCanonicalSession(
            HazelcastAuthStateStore.AuthSession session, RefreshTokenClaims presentedClaims, String presentedToken) {
        var projection = authFlowService.currentRefreshSession(presentedClaims.sessionId(),
                presentedClaims.userId(), replayCipher);
        if (projection == null || !projection.sid().equals(presentedClaims.sessionId())
                || !projection.userId().equals(presentedClaims.userId())
                || session.status() != HazelcastAuthStateStore.SessionStatus.ACTIVE
                || !session.userId().equals(projection.userId())) {
            throw new AuthStateUnavailableException();
        }
        RefreshTokenClaims canonicalClaims = tokenLocator.parseRefreshToken(projection.refreshToken());
        String canonicalHash = authStateStore.tokenHash(projection.refreshToken());
        if (!canonicalClaims.sessionId().equals(presentedClaims.sessionId())
                || !canonicalClaims.userId().equals(presentedClaims.userId())) {
            throw new AuthStateUnavailableException();
        }
        if (!canonicalHash.equals(authStateStore.tokenHash(presentedToken))) {
            throw new AuthStateConflictException("AUTH_REFRESH_STALE");
        }
        boolean currentProjection = canonicalClaims.tokenId() != null
                && authStateStore.jtiHash(canonicalClaims.tokenId()).equals(session.currentJtiHash())
                && canonicalHash.equals(session.currentRefreshHash());
        boolean replayMetadataClear = session.previousJtiHash() == null
                && session.previousRefreshHash() == null
                && session.lastRefreshOperationId() == null
                && session.replayUntil() == null
                && session.encryptedReplayRefreshToken() == null;
        if (currentProjection && replayMetadataClear) {
            return session;
        }
        HazelcastAuthStateStore.AuthSession repaired = session.withCanonicalRefresh(
                authStateStore.jtiHash(canonicalClaims.tokenId()), canonicalHash, Instant.now(),
                projection.idleExpiry(), projection.absoluteExpiry());
        return authStateStore.replaceSession(session, repaired) ? repaired : null;
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
            tokenLocator.logRefreshFailure("user_unavailable");
            throw exception;
        }
    }
}
