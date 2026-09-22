package com.muse.meomuneum.auth.service;

import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;

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
    private final RefreshTokenSessionService refreshTokenSessionService;
    private final UserAuthenticationService userAuthenticationService;

    public AuthService(JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties,
            RefreshTokenCookieFactory refreshTokenCookieFactory, RefreshTokenSessionService refreshTokenSessionService,
            UserAuthenticationService userAuthenticationService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenCookieFactory = refreshTokenCookieFactory;
        this.refreshTokenSessionService = refreshTokenSessionService;
        this.userAuthenticationService = userAuthenticationService;
    }

    public TokenResponse login(LoginRequest request, HttpServletRequest servletRequest, HttpHeaders headers) {
        User user = userAuthenticationService.authenticate(request.email(), request.password());
        servletRequest.getSession(true);
        servletRequest.changeSessionId();
        TokenResponse response = issueTokens(user, servletRequest, headers);
        log.info(
                "event=auth_login_succeeded domainCode={} httpStatus={} userId={} requestId={}",
                AuthSuccessCode.LOGIN_SUCCESS.code(),
                AuthSuccessCode.LOGIN_SUCCESS.status().value(),
                user.getId(),
                MDC.get("requestId")
        );
        return response;
    }

    public TokenResponse refresh(HttpServletRequest request, HttpHeaders headers) {
        try {
            String refreshToken = getRefreshToken(request);
            RefreshTokenClaims currentClaims = jwtTokenProvider.parseRefreshToken(refreshToken);
            User user = userAuthenticationService.findActiveUser(currentClaims.userId());
            return rotateTokens(user, request, headers, refreshToken, currentClaims);
        } catch (AuthenticationFailedException exception) {
            refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
            throw exception;
        }
    }

    public void logout(HttpHeaders headers) {
        refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
    }

    private TokenResponse issueTokens(User user, HttpServletRequest request, HttpHeaders headers) {
        String refreshToken = jwtTokenProvider.createRefreshToken(user);
        RefreshTokenClaims refreshTokenClaims = jwtTokenProvider.parseRefreshToken(refreshToken);
        refreshTokenSessionService.register(request, refreshTokenClaims, refreshToken);
        refreshTokenCookieFactory.addRefreshTokenCookie(headers, refreshToken);
        return new TokenResponse(
                jwtTokenProvider.createAccessToken(user),
                jwtProperties.accessTokenExpirationSeconds());
    }

    private TokenResponse rotateTokens(User user, HttpServletRequest request, HttpHeaders headers,
            String currentRefreshToken, RefreshTokenClaims currentClaims) {
        String nextRefreshToken = jwtTokenProvider.createRefreshToken(user);
        RefreshTokenClaims nextClaims = jwtTokenProvider.parseRefreshToken(nextRefreshToken);
        refreshTokenSessionService.rotate(
                request, currentClaims, currentRefreshToken, nextClaims, nextRefreshToken);
        refreshTokenCookieFactory.addRefreshTokenCookie(headers, nextRefreshToken);
        return new TokenResponse(
                jwtTokenProvider.createAccessToken(user),
                jwtProperties.accessTokenExpirationSeconds());
    }

    private String getRefreshToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            throw new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN);
        }

        return Arrays.stream(request.getCookies())
                .filter(cookie -> REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName()))
                .map(cookie -> cookie.getValue())
                .findFirst()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN));
    }
}
