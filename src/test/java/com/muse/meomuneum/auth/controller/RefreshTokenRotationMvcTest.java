package com.muse.meomuneum.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.auth.service.AuthService;
import com.muse.meomuneum.auth.service.CsrfTokenService;
import com.muse.meomuneum.auth.service.RefreshTokenCookieFactory;
import com.muse.meomuneum.auth.service.RefreshTokenSessionService;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.service.UserAuthenticationService;

class RefreshTokenRotationMvcTest {

    private static final String JWT_SECRET = "development-only-secret-with-at-least-32-bytes";

    private JwtTokenProvider jwt;
    private UserAuthenticationService users;
    private User user;
    private RefreshTokenSessionService sessions;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties("project-api", "project-api", JWT_SECRET, 3600, 1209600);
        jwt = new JwtTokenProvider(properties);
        users = mock(UserAuthenticationService.class);
        sessions = new RefreshTokenSessionService();
        user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getRole()).thenReturn(UserRole.USER);
        when(users.findActiveUser(1L)).thenReturn(user);
        AuthService authService = new AuthService(jwt, properties, new RefreshTokenCookieFactory(properties, true),
                users, sessions);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, mock(CsrfTokenService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void refreshRequiresBoundSessionAndDoesNotRotateRefreshCookie() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String refresh = registerRefreshToken(session);
        MvcResult first = mockMvc.perform(post("/api/v1/auth/token/refresh").session(session)
                .cookie(new Cookie("refresh_token", refresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("token refreshed"))
                .andExpect(jsonPath("$.data.access_token").exists())
                .andExpect(jsonPath("$.data.expires_in").value(3600)).andReturn();
        MvcResult second = mockMvc.perform(post("/api/v1/auth/token/refresh").session(session)
                .cookie(new Cookie("refresh_token", refresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("token refreshed"))
                .andExpect(jsonPath("$.data.access_token").exists())
                .andExpect(jsonPath("$.data.expires_in").value(3600)).andReturn();

        assertThat(first.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(second.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void refreshRejectsMissingSession() throws Exception {
        String refresh = jwt.createRefreshToken(user, "session-without-server-state");
        mockMvc.perform(post("/api/v1/auth/token/refresh").cookie(new Cookie("refresh_token", refresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid refresh token"));
    }

    @Test
    void logoutRevokesMatchingRefreshSessionAndDeletesCookie() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String refresh = registerRefreshToken(session);
        String access = jwt.createAccessToken(user, session.getId());

        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout").session(session)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .cookie(new Cookie("refresh_token", refresh)))
                .andExpect(status().isNoContent()).andReturn();

        assertThat(session.isInvalid()).isTrue();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=", "Max-Age=0");
    }

    @Test
    void logoutLocatorMismatchPreservesSessionAndCookie() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String refresh = registerRefreshToken(session);
        String otherSessionAccess = jwt.createAccessToken(user, "another-session-id");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout").session(session)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherSessionAccess)
                .cookie(new Cookie("refresh_token", refresh)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("session mismatch")).andReturn();

        assertThat(session.isInvalid()).isFalse();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void refreshEndpointRejectsGetWithMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/auth/token/refresh"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message").value("method not allowed"));
    }

    private String registerRefreshToken(MockHttpSession session) {
        String refresh = jwt.createRefreshToken(user, session.getId());
        sessions.register(session, jwt.parseRefreshToken(refresh));
        return refresh;
    }
}
