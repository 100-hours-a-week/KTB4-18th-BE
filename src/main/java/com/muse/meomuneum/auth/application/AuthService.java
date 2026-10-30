package com.muse.meomuneum.auth.application;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.queue.AuthQueueCookieFactory;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Service
public class AuthService {

    private final QueuedLoginService queuedLoginService;
    private final AuthSessionService authSessionService;
    private final AuthFlowService authFlowService;
    private final JwtTokenProvider jwtTokenProvider;

    @Autowired
    public AuthService(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService,
            ApplicationEventPublisher events,
            HazelcastAuthStateStore authStateStore, RefreshReplayCipher replayCipher,
            AuthQueueCookieFactory queueCookieFactory, AuthStateProperties authStateProperties,
            AuthFlowService authFlowService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.authFlowService = authFlowService;
        this.queuedLoginService = new QueuedLoginService(jwtTokenProvider, jwtProperties,
                refreshTokenCookieFactory, userAuthenticationService, authStateStore, replayCipher,
                queueCookieFactory, authStateProperties);
        this.authSessionService = new AuthSessionService(jwtTokenProvider, jwtProperties,
                refreshTokenCookieFactory, userAuthenticationService, events, authStateStore, replayCipher,
                authFlowService,
                authStateProperties);
    }

    public AuthService(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService,
            ApplicationEventPublisher events,
            HazelcastAuthStateStore authStateStore, RefreshReplayCipher replayCipher,
            AuthQueueCookieFactory queueCookieFactory, AuthStateProperties authStateProperties) {
        this(jwtTokenProvider, jwtProperties, refreshTokenCookieFactory, userAuthenticationService, events,
                authStateStore, replayCipher, queueCookieFactory, authStateProperties, null);
    }

    public TokenResponse login(LoginRequest request, HttpServletRequest servletRequest, HttpHeaders headers) {
        return queuedLoginService.login(request, servletRequest, headers);
    }

    public QueuedLoginService.LoginResult loginWithTicket(LoginRequest request, HttpServletRequest servletRequest,
            HttpHeaders headers) {
        return queuedLoginService.loginWithTicket(request, servletRequest, headers);
    }

    public TokenResponse refresh(HttpServletRequest request, HttpHeaders headers) {
        return authSessionService.refresh(request, headers);
    }

    public TokenResponse replayCommittedRefresh(String refreshToken, HttpHeaders headers) {
        return authSessionService.replayCommittedRefresh(refreshToken, headers);
    }

    public TokenResponse recoverLogin(HttpServletRequest request, HttpHeaders headers) {
        return queuedLoginService.recoverLogin(request, headers);
    }

    public void revokeCurrentSession(HttpServletRequest request, HttpHeaders headers, Long expectedUserId) {
        if (authFlowService != null) {
            TokenClaims claims = accessClaims(request);
            if (claims != null) {
                authFlowService.revokeSession(claims.sessionId(), expectedUserId, "PASSWORD_OR_DEVICE");
            }
        }
        authSessionService.revokeCurrentSession(request, headers, expectedUserId);
    }

    public java.util.List<HazelcastAuthStateStore.AuthSession> listSessions(Long userId) {
        return authSessionService.listSessions(userId);
    }

    public boolean revokeSession(Long userId, String sid) {
        if (authFlowService != null) {
            authFlowService.revokeSession(sid, userId, "DEVICE_REMOVAL");
            authSessionService.publishSessionRevocation(userId, sid);
            return true;
        }
        return authSessionService.revokeSession(userId, sid);
    }

    public void logout(HttpServletRequest request, HttpHeaders headers) {
        // With centralized flows, commitLogout already revokes the session attached to the
        // flow transactionally. Replaying its receipt must never revoke a newer bearer SID.
        authSessionService.logout(request, headers);
    }

    public void revokeAllSessions(Long userId, String reason) {
        if (authFlowService != null) {
            authFlowService.revokeAllSessions(userId, reason)
                    .forEach(sid -> authSessionService.publishSessionRevocation(userId, sid));
        }
    }

    private TokenClaims accessClaims(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            return null;
        }
        try {
            return jwtTokenProvider.parseAccessTokenForLogout(authorization.substring(7));
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
