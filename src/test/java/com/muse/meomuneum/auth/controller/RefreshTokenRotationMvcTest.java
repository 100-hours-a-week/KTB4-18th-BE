package com.muse.meomuneum.auth.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.auth.application.AuthService;
import com.muse.meomuneum.auth.application.CsrfTokenService;
import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.application.RefreshTokenCookieFactory;
import com.muse.meomuneum.auth.config.AuthStateProperties;
import com.muse.meomuneum.auth.exception.AuthExceptionHandler;
import com.muse.meomuneum.auth.queue.AuthQueueCookieFactory;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.user.service.UserAuthenticationService;

class RefreshTokenRotationMvcTest {

    private static final String JWT_SECRET = "development-only-secret-with-at-least-32-bytes";

    private JwtTokenProvider jwt;
    private HazelcastAuthStateStore authStateStore;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties("project-api", "project-api", JWT_SECRET, 3600, 1209600);
        jwt = new JwtTokenProvider(jwtProperties);
        authStateStore = mock(HazelcastAuthStateStore.class);
        AuthStateProperties authStateProperties = new AuthStateProperties("01234567890123456789012345678901",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "MEO_QUEUE_V2", 1209600, 7776000,
                900, 60, 900, 20, 10, 64, 16, 4, 120, 1800, 5, 20, 4000, 86400, 1, 100000, 300, 45);
        AuthService authService = new AuthService(jwt, jwtProperties,
                new RefreshTokenCookieFactory(jwtProperties, true), mock(UserAuthenticationService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class), authStateStore,
                new RefreshReplayCipher(authStateProperties), mock(AuthQueueCookieFactory.class), authStateProperties);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, mock(CsrfTokenService.class)))
                .setControllerAdvice(new AuthExceptionHandler()).build();
    }

    @Test
    void refreshRequiresStableOperationIdAndDoesNotUseServletSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/refresh"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("INVALID_OPERATION_ID"));

        verify(authStateStore).acquireRequestPermit();
    }

    @Test
    void refreshLooksUpCentralSessionStateEvenWhenRequestHasServletSession() throws Exception {
        long userId = 1L;
        String sid = UUID.randomUUID().toString();
        String refresh = jwt.createRefreshToken(mockUser(userId), sid);
        MockHttpSession session = new MockHttpSession();
        String operationId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/auth/token/refresh").session(session).cookie(new Cookie("refresh_token", refresh))
                .header("X-Refresh-Operation-Id", operationId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid refresh token"));

        verify(authStateStore).getSession(sid);
    }

    private com.muse.meomuneum.user.domain.User mockUser(long userId) {
        com.muse.meomuneum.user.domain.User user = mock(com.muse.meomuneum.user.domain.User.class);
        org.mockito.Mockito.when(user.getId()).thenReturn(userId);
        org.mockito.Mockito.when(user.getRole()).thenReturn(com.muse.meomuneum.user.domain.UserRole.USER);
        return user;
    }
}
