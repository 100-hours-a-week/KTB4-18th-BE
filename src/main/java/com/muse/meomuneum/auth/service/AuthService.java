package com.muse.meomuneum.auth.service;

import java.util.Arrays;

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

    public AuthService(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenCookieFactory = refreshTokenCookieFactory;
        this.userAuthenticationService = userAuthenticationService;
    }

    public TokenResponse login(LoginRequest request, HttpServletRequest servletRequest, HttpHeaders headers) {
        User user = userAuthenticationService.authenticate(request.email(), request.password());
        if (servletRequest.getSession(false) != null) {
            servletRequest.changeSessionId();
        }
        TokenResponse response = issueTokens(user, headers);
        log.info(
                "event=auth_login_succeeded domainCode={} httpStatus={} userId={} requestId={}",
                AuthSuccessCode.LOGIN_SUCCESS.code(),
                AuthSuccessCode.LOGIN_SUCCESS.status().value(),
                user.getId(),
                MDC.get("requestId"));
        return response;
    }

    public TokenResponse refresh(HttpServletRequest request, HttpHeaders headers) {
        String refreshToken = getRefreshToken(request);
        var currentClaims = parseRefreshToken(refreshToken);
        User user;
        try {
            user = userAuthenticationService.findActiveUser(currentClaims.userId());
        } catch (AuthenticationFailedException exception) {
            logRefreshFailure("user_unavailable");
            throw exception;
        }
        return issueTokens(user, headers);
    }

    public void logout(HttpServletRequest request, HttpHeaders headers) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
    }

    private TokenResponse issueTokens(User user, HttpHeaders headers) {
        String refreshToken = jwtTokenProvider.createRefreshToken(user);
        refreshTokenCookieFactory.addRefreshTokenCookie(headers, refreshToken);
        return new TokenResponse(
                jwtTokenProvider.createAccessToken(user),
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

    private AuthenticationFailedException invalidRefreshToken(String reason) {
        logRefreshFailure(reason);
        return new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN);
    }

    private void logRefreshFailure(String reason) {
        log.warn("event=auth_refresh_rejected reason={} requestId={}", reason, MDC.get("requestId"));
    }
}
