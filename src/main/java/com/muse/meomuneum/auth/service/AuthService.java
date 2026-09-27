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
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Service
public class AuthService {

    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";
    private static final String AUTHENTICATED_USER_ID_ATTRIBUTE = "authenticatedUserId";
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
        HttpSession session = servletRequest.getSession(true);
        servletRequest.changeSessionId();
        session.setAttribute(AUTHENTICATED_USER_ID_ATTRIBUTE, user.getId());
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
        try {
            String refreshToken = getRefreshToken(request);
            var currentClaims = jwtTokenProvider.parseRefreshToken(refreshToken);
            requireSessionUser(request, currentClaims.userId());
            User user = userAuthenticationService.findActiveUser(currentClaims.userId());
            return issueTokens(user, headers);
        } catch (AuthenticationFailedException exception) {
            refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
            throw exception;
        }
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
            throw new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN);
        }

        return Arrays.stream(request.getCookies())
                .filter(cookie -> REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName()))
                .map(cookie -> cookie.getValue())
                .findFirst()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN));
    }

    private void requireSessionUser(HttpServletRequest request, Long userId) {
        HttpSession session = request.getSession(false);
        if (session == null || !userId.equals(session.getAttribute(AUTHENTICATED_USER_ID_ATTRIBUTE))) {
            throw new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN);
        }
    }
}
