package com.muse.meomuneum.auth.service;

import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@Service
public class AuthService {

    private static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;
    private final UserAuthenticationService userAuthenticationService;

    public AuthService(JwtTokenProvider jwtTokenProvider, RefreshTokenCookieFactory refreshTokenCookieFactory,
            UserAuthenticationService userAuthenticationService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenCookieFactory = refreshTokenCookieFactory;
        this.userAuthenticationService = userAuthenticationService;
    }

    public TokenResponse login(LoginRequest request, HttpServletRequest servletRequest, HttpHeaders headers) {
        User user = userAuthenticationService.authenticate(request.email(), request.password());
        servletRequest.changeSessionId();
        return issueTokens(user, headers);
    }

    public TokenResponse refresh(HttpServletRequest request, HttpHeaders headers) {
        String refreshToken = getRefreshToken(request);
        Long userId = jwtTokenProvider.parseRefreshToken(refreshToken);
        User user = userAuthenticationService.findActiveUser(userId);
        return issueTokens(user, headers);
    }

    public void logout(HttpHeaders headers) {
        refreshTokenCookieFactory.deleteRefreshTokenCookie(headers);
    }

    private TokenResponse issueTokens(User user, HttpHeaders headers) {
        String refreshToken = jwtTokenProvider.createRefreshToken(user);
        refreshTokenCookieFactory.addRefreshTokenCookie(headers, refreshToken);
        return new TokenResponse(jwtTokenProvider.createAccessToken(user), 3600);
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
