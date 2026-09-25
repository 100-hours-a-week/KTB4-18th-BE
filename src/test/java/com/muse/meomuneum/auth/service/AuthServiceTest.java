package com.muse.meomuneum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;

class AuthServiceTest {

    private JwtTokenProvider jwtTokenProvider;
    private RefreshTokenCookieFactory refreshTokenCookieFactory;
    private UserAuthenticationService userAuthenticationService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        refreshTokenCookieFactory = mock(RefreshTokenCookieFactory.class);
        userAuthenticationService = mock(UserAuthenticationService.class);
        authService = new AuthService(
                jwtTokenProvider,
                new JwtProperties("project-api", "project-api", "test-secret", 3600, 1209600),
                refreshTokenCookieFactory,
                userAuthenticationService
        );
    }

    @Test
    void loginIssuesStatelessRefreshCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpHeaders headers = new HttpHeaders();
        User user = createUser();
        RefreshTokenClaims refreshTokenClaims = claims();
        when(userAuthenticationService.authenticate("user@example.com", "password")).thenReturn(user);
        when(jwtTokenProvider.createRefreshToken(user)).thenReturn("login-refresh-token");
        when(jwtTokenProvider.parseRefreshToken("login-refresh-token")).thenReturn(refreshTokenClaims);
        when(jwtTokenProvider.createAccessToken(user)).thenReturn("access-token");

        TokenResponse response = authService.login(new LoginRequest("user@example.com", "password"), request, headers);

        assertThat(response).isEqualTo(new TokenResponse("access-token", 3600));
        assertThat(request.getSession(false)).isNotNull();
        verify(refreshTokenCookieFactory).addRefreshTokenCookie(headers, "login-refresh-token");
    }

    @Test
    void refreshUsesSignedCookieWithoutSessionState() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "current-refresh-token"));
        HttpHeaders headers = new HttpHeaders();
        User user = createUser();
        RefreshTokenClaims currentClaims = claims();
        RefreshTokenClaims nextClaims = claims();
        when(jwtTokenProvider.parseRefreshToken("current-refresh-token")).thenReturn(currentClaims);
        when(userAuthenticationService.findActiveUser(1L)).thenReturn(user);
        when(jwtTokenProvider.createRefreshToken(user)).thenReturn("next-refresh-token");
        when(jwtTokenProvider.parseRefreshToken("next-refresh-token")).thenReturn(nextClaims);
        when(jwtTokenProvider.createAccessToken(user)).thenReturn("access-token");

        TokenResponse response = authService.refresh(request, headers);

        assertThat(response).isEqualTo(new TokenResponse("access-token", 3600));
        verify(refreshTokenCookieFactory).addRefreshTokenCookie(headers, "next-refresh-token");
    }

    @Test
    void refreshFailureDeletesRefreshTokenCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "invalid-refresh-token"));
        HttpHeaders headers = new HttpHeaders();
        when(jwtTokenProvider.parseRefreshToken("invalid-refresh-token"))
                .thenThrow(new AuthenticationFailedException(AuthErrorCode.REFRESH_INVALID_TOKEN));

        assertThatThrownBy(() -> authService.refresh(request, headers))
                .isInstanceOf(AuthenticationFailedException.class)
                .extracting(exception -> ((AuthenticationFailedException) exception).getErrorCode())
                .isEqualTo(AuthErrorCode.REFRESH_INVALID_TOKEN);

        verify(refreshTokenCookieFactory).deleteRefreshTokenCookie(headers);
    }

    @Test
    void refreshWithoutSessionStillIssuesAccessToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "current-refresh-token"));
        HttpHeaders headers = new HttpHeaders();
        User user = createUser();
        RefreshTokenClaims currentClaims = claims();
        RefreshTokenClaims nextClaims = claims();
        when(jwtTokenProvider.parseRefreshToken("current-refresh-token")).thenReturn(currentClaims);
        when(userAuthenticationService.findActiveUser(1L)).thenReturn(user);
        when(jwtTokenProvider.createRefreshToken(user)).thenReturn("next-refresh-token");
        when(jwtTokenProvider.parseRefreshToken("next-refresh-token")).thenReturn(nextClaims);
        when(jwtTokenProvider.createAccessToken(user)).thenReturn("access-token");

        assertThat(authService.refresh(request, headers))
                .isEqualTo(new TokenResponse("access-token", 3600));
        verify(refreshTokenCookieFactory).addRefreshTokenCookie(headers, "next-refresh-token");
    }

    private RefreshTokenClaims claims() {
        return new RefreshTokenClaims(1L, Instant.now().plusSeconds(1209600));
    }

    private User createUser() {
        return mock(User.class);
    }
}
