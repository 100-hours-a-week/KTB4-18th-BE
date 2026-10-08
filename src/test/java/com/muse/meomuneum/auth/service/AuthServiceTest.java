package com.muse.meomuneum.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.RefreshTokenClaims;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;

class AuthServiceTest {

    private JwtTokenProvider jwt;
    private RefreshTokenCookieFactory cookieFactory;
    private UserAuthenticationService users;
    private RefreshTokenSessionService sessions;
    private AuthService service;
    private org.springframework.context.ApplicationEventPublisher events;
    private User user;

    @BeforeEach
    void setUp() {
        jwt = mock(JwtTokenProvider.class);
        cookieFactory = mock(RefreshTokenCookieFactory.class);
        users = mock(UserAuthenticationService.class);
        sessions = new RefreshTokenSessionService();
        events = mock(org.springframework.context.ApplicationEventPublisher.class);
        service = new AuthService(jwt, new JwtProperties("project-api", "project-api", "test-secret", 3600, 1209600),
                cookieFactory, users, sessions, events);
        user = mock(User.class);
        when(user.getId()).thenReturn(1L);
    }

    @Test
    void loginCreatesSessionBoundStableRefreshAndAccessTokens() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpHeaders headers = new HttpHeaders();
        when(users.authenticate("user@example.com", "password")).thenReturn(user);
        when(jwt.createRefreshToken(eq(user), anyString())).thenReturn("refresh-token");
        when(jwt.parseRefreshToken("refresh-token")).thenAnswer(invocation -> claims(1L,
                request.getSession(false).getId(), "stable-refresh-jti"));
        when(jwt.createAccessToken(eq(user), anyString())).thenReturn("access-token");

        TokenResponse response = service.login(new LoginRequest("user@example.com", "password"), request, headers);

        assertThat(response).isEqualTo(new TokenResponse("access-token", 3600));
        assertThat(request.getSession(false).getAttribute(RefreshTokenSessionService.TOKEN_ID_ATTRIBUTE))
                .isEqualTo("stable-refresh-jti");
        verify(cookieFactory).addRefreshTokenCookie(headers, "refresh-token");
    }

    @Test
    void refreshValidatesSessionAndReusesRefreshCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(true);
        RefreshTokenClaims claims = claims(1L, session.getId(), "stable-refresh-jti");
        sessions.register(session, claims);
        request.setCookies(new Cookie("refresh_token", "refresh-token"));
        HttpHeaders headers = new HttpHeaders();
        when(jwt.parseRefreshToken("refresh-token")).thenReturn(claims);
        when(users.findActiveUser(1L)).thenReturn(user);
        when(jwt.createAccessToken(user, session.getId())).thenReturn("access-token");

        assertThat(service.refresh(request, headers)).isEqualTo(new TokenResponse("access-token", 3600));
        verify(cookieFactory, never()).addRefreshTokenCookie(headers, "refresh-token");
        verify(users).findActiveUser(1L);
    }

    @Test
    void logoutChecksBothSignedLocatorsBeforeRevokingSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        var session = request.getSession(true);
        RefreshTokenClaims refresh = claims(1L, session.getId(), "stable-refresh-jti");
        sessions.register(session, refresh);
        request.setCookies(new Cookie("refresh_token", "refresh-token"));
        request.addHeader("Authorization", "Bearer access-token");
        when(jwt.parseAccessTokenForLogout("access-token"))
                .thenReturn(new TokenClaims(1L, List.of("USER"), Instant.now(), session.getId(), "access-jti"));
        when(jwt.parseRefreshTokenForLogout("refresh-token")).thenReturn(refresh);
        HttpHeaders headers = new HttpHeaders();

        service.logout(request, headers);

        assertThat(request.getSession(false)).isNull();
        verify(events).publishEvent(new ChatLogoutEvent(1L));
        verify(cookieFactory).deleteRefreshTokenCookie(headers);
    }

    @Test
    void logoutMismatchDoesNotInvalidateSessionOrDeleteCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(true);
        RefreshTokenClaims refresh = claims(1L, session.getId(), "stable-refresh-jti");
        sessions.register(session, refresh);
        request.setCookies(new Cookie("refresh_token", "refresh-token"));
        request.addHeader("Authorization", "Bearer access-token");
        when(jwt.parseAccessTokenForLogout("access-token"))
                .thenReturn(new TokenClaims(1L, List.of("USER"), Instant.now(), "different-session", "access-jti"));
        when(jwt.parseRefreshTokenForLogout("refresh-token")).thenReturn(refresh);

        assertThatThrownBy(() -> service.logout(request, new HttpHeaders()))
                .isInstanceOf(AuthenticationFailedException.class)
                .extracting(error -> ((AuthenticationFailedException) error).getErrorCode())
                .isEqualTo(AuthErrorCode.LOGOUT_SESSION_MISMATCH);
        assertThat(session.isInvalid()).isFalse();
        verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
        verify(cookieFactory, never()).deleteRefreshTokenCookie(org.mockito.ArgumentMatchers.any());
    }

    private RefreshTokenClaims claims(Long userId, String sessionId, String tokenId) {
        return new RefreshTokenClaims(userId, Instant.now().plusSeconds(1209600), sessionId, tokenId);
    }
}
