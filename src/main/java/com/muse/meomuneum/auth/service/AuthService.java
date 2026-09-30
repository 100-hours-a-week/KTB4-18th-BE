package com.muse.meomuneum.auth.service;

import java.util.Arrays;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.response.AuthSuccessCode;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Service
public class AuthService {

    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;
    private final UserAuthenticationService userAuthenticationService;
    private final RefreshTokenSessionService refreshTokenSessionService;

    public AuthService(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService,
            RefreshTokenSessionService refreshTokenSessionService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenCookieFactory = refreshTokenCookieFactory;
        this.userAuthenticationService = userAuthenticationService;
        this.refreshTokenSessionService = refreshTokenSessionService;
    }

    public TokenResponse login(LoginRequest request, HttpServletRequest servletRequest, HttpHeaders headers) {
        User user = userAuthenticationService.authenticate(request.email(), request.password());
        while (true) {
            HttpSession session = servletRequest.getSession(true);
            synchronized (session) {
                try {
                    if (!session.isNew()) {
                        servletRequest.changeSessionId();
                    }
                } catch (IllegalStateException expiredDuringLogin) {
                    // A concurrent logout invalidated this session; retry against the new request session.
                    continue;
                }
                return issueTokens(user, session, headers);
            }
        }
    }

    public TokenResponse refresh(HttpServletRequest request, HttpHeaders headers) {
        String refreshToken = getRefreshToken(request);
        var currentClaims = parseRefreshToken(refreshToken);
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw invalidRefreshToken("refresh_session_missing");
        }
        synchronized (session) {
            refreshTokenSessionService.validate(session, currentClaims);
            User user;
            try {
                user = userAuthenticationService.findActiveUser(currentClaims.userId());
            } catch (AuthenticationFailedException exception) {
                logRefreshFailure("user_unavailable");
                throw exception;
            }
            return new TokenResponse(jwtTokenProvider.createAccessToken(user, session.getId()),
                    jwtProperties.accessTokenExpirationSeconds());
        }
    }

    public void logout(HttpServletRequest request, HttpHeaders headers) {
        Optional<TokenClaims> accessLocator = getAccessTokenLocator(request);
        Optional<RefreshTokenClaims> refreshLocator = getRefreshTokenLogoutLocator(request);
        HttpSession session = request.getSession(false);
        if (session != null) {
            synchronized (session) {
                boolean accessMatches = accessLocator.map(claims -> refreshTokenSessionService
                        .matchesAccessLocator(session, claims)).orElse(true);
                boolean refreshMatches = refreshLocator.map(claims -> refreshTokenSessionService
                        .matchesRefreshLocator(session, claims)).orElse(true);
                if (!accessMatches || !refreshMatches) {
                    throw new AuthenticationFailedException(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
                }
                refreshTokenSessionService.clear(session);
                session.invalidate();
            }
        }
        refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
    }

    private TokenResponse issueTokens(User user, HttpSession session, HttpHeaders headers) {
        String sessionId = session.getId();
        String refreshToken = jwtTokenProvider.createRefreshToken(user, sessionId);
        RefreshTokenClaims claims = jwtTokenProvider.parseRefreshToken(refreshToken);
        refreshTokenSessionService.register(session, claims);
        refreshTokenCookieFactory.addRefreshTokenCookie(headers, refreshToken);
        log.info("event=auth_login_succeeded domainCode={} httpStatus={} userId={} requestId={}",
                AuthSuccessCode.LOGIN_SUCCESS.code(), AuthSuccessCode.LOGIN_SUCCESS.status().value(), user.getId(),
                MDC.get("requestId"));
        return new TokenResponse(jwtTokenProvider.createAccessToken(user, sessionId),
                jwtProperties.accessTokenExpirationSeconds());
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
