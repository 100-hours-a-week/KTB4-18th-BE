package com.muse.meomuneum.auth.application;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Service
public class AuthService {

    private final AuthSessionService authSessionService;
    private final AuthFlowService authFlowService;
    private final JwtTokenProvider jwtTokenProvider;

    @Autowired
    public AuthService(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService,
            ApplicationEventPublisher events,
            HazelcastAuthStateStore authStateStore, RefreshReplayCipher replayCipher,
            AuthStateProperties authStateProperties, AuthFlowService authFlowService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.authFlowService = authFlowService;
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
            AuthStateProperties authStateProperties) {
        this(jwtTokenProvider, jwtProperties, refreshTokenCookieFactory, userAuthenticationService, events,
                authStateStore, replayCipher, authStateProperties, null);
    }

    public TokenResponse refresh(HttpServletRequest request, HttpHeaders headers) {
        return authSessionService.refresh(request, headers);
    }

    public TokenResponse replayCommittedRefresh(String refreshToken, HttpHeaders headers) {
        return authSessionService.replayCommittedRefresh(refreshToken, headers);
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
        authSessionService.logout(request, headers);
    }

    public void revokeAllSessions(Long userId, String reason) {
        if (authFlowService != null) {
            // Deliver revocations from the outbox after the withdrawal transaction commits.
            authFlowService.revokeAllSessions(userId, reason);
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
