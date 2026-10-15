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
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Service
public class AuthService {

    private final QueuedLoginService queuedLoginService;
    private final AuthSessionService authSessionService;

    @Autowired
    public AuthService(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService,
            ApplicationEventPublisher events,
            HazelcastAuthStateStore authStateStore, RefreshReplayCipher replayCipher,
            AuthQueueCookieFactory queueCookieFactory, AuthStateProperties authStateProperties) {
        this.queuedLoginService = new QueuedLoginService(jwtTokenProvider, jwtProperties,
                refreshTokenCookieFactory, userAuthenticationService, authStateStore, replayCipher,
                queueCookieFactory, authStateProperties);
        this.authSessionService = new AuthSessionService(jwtTokenProvider, jwtProperties,
                refreshTokenCookieFactory, userAuthenticationService, events, authStateStore, replayCipher,
                authStateProperties);
    }

    public TokenResponse login(LoginRequest request, HttpServletRequest servletRequest, HttpHeaders headers) {
        return queuedLoginService.login(request, servletRequest, headers);
    }

    public TokenResponse refresh(HttpServletRequest request, HttpHeaders headers) {
        return authSessionService.refresh(request, headers);
    }

    public TokenResponse recoverLogin(HttpServletRequest request, HttpHeaders headers) {
        return queuedLoginService.recoverLogin(request, headers);
    }

    public void revokeCurrentSession(HttpServletRequest request, HttpHeaders headers, Long expectedUserId) {
        authSessionService.revokeCurrentSession(request, headers, expectedUserId);
    }

    public java.util.List<HazelcastAuthStateStore.AuthSession> listSessions(Long userId) {
        return authSessionService.listSessions(userId);
    }

    public boolean revokeSession(Long userId, String sid) {
        return authSessionService.revokeSession(userId, sid);
    }

    public void logout(HttpServletRequest request, HttpHeaders headers) {
        authSessionService.logout(request, headers);
    }
}
