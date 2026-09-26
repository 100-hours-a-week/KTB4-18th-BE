package com.muse.meomuneum.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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

import com.muse.meomuneum.auth.exception.AuthExceptionHandler;
import com.muse.meomuneum.auth.service.AuthService;
import com.muse.meomuneum.auth.service.CsrfTokenService;
import com.muse.meomuneum.auth.service.RefreshTokenCookieFactory;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.service.UserAuthenticationService;

class RefreshTokenRotationMvcTest {

    private static final String JWT_SECRET = "development-only-secret-with-at-least-32-bytes";

    private JwtTokenProvider jwtTokenProvider;
    private UserAuthenticationService userAuthenticationService;
    private User user;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties("project-api", "project-api", JWT_SECRET, 3600, 1209600);
        jwtTokenProvider = new JwtTokenProvider(jwtProperties);
        userAuthenticationService = mock(UserAuthenticationService.class);
        user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getRole()).thenReturn(UserRole.USER);
        when(userAuthenticationService.findActiveUser(1L)).thenReturn(user);
        AuthService authService = new AuthService(
                jwtTokenProvider,
                jwtProperties,
                new RefreshTokenCookieFactory(jwtProperties),
                userAuthenticationService);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, mock(CsrfTokenService.class)))
                .setControllerAdvice(new AuthExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void refreshAcceptsValidSignedTokenForMatchingSessionUser() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("authenticatedUserId", 1L);
        String currentRefreshToken = registerRefreshToken(session);

        MvcResult success = mockMvc.perform(post("/api/v1/auth/token/refresh")
                .session(session)
                .cookie(new Cookie("refresh_token", currentRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("token refreshed"))
                .andExpect(jsonPath("$.data.access_token").exists())
                .andExpect(jsonPath("$.data.expires_in").value(3600))
                .andReturn();

        assertThat(success.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=");
        assertThat(session.isInvalid()).isFalse();

        MvcResult second = mockMvc.perform(post("/api/v1/auth/token/refresh")
                .session(session)
                .cookie(new Cookie("refresh_token", currentRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("token refreshed"))
                .andReturn();

        assertThat(second.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=");
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void refreshWithoutSessionRejectsSignedRefreshToken() throws Exception {
        String refreshToken = registerRefreshToken(new MockHttpSession());

        MvcResult success = mockMvc.perform(post("/api/v1/auth/token/refresh")
                .cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid refresh token"))
                .andReturn();

        assertThat(success.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=", "Max-Age=0");
    }

    @Test
    void logoutInvalidatesSessionAndDeletesRefreshTokenCookie() throws Exception {
        MockHttpSession session = new MockHttpSession();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout").session(session))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(session.isInvalid()).isTrue();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=", "Max-Age=0");
    }

    private String registerRefreshToken(MockHttpSession session) {
        String refreshToken = jwtTokenProvider.createRefreshToken(user);
        return refreshToken;
    }
}
