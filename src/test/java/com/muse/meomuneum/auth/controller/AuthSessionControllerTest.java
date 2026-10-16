package com.muse.meomuneum.auth.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.auth.application.AuthService;
import com.muse.meomuneum.auth.application.RefreshTokenCookieFactory;
import com.muse.meomuneum.auth.exception.AuthExceptionHandler;
import com.muse.meomuneum.global.security.JwtTokenProvider;

class AuthSessionControllerTest {

    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        AuthSessionController controller = new AuthSessionController(authService,
                mock(JwtTokenProvider.class), mock(RefreshTokenCookieFactory.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AuthExceptionHandler()).build();
    }

    @Test
    void listReturnsCoarseDeviceLabelWithoutTokenMaterial() throws Exception {
        var session = new com.muse.meomuneum.auth.service.HazelcastAuthStateStore.AuthSession(5L, "sid-1",
                "Chrome on macOS", com.muse.meomuneum.auth.service.HazelcastAuthStateStore.SessionStatus.ACTIVE,
                "jti-hash", "refresh-hash", null, null, null, null, null,
                "login-operation", Instant.now().plusSeconds(60), 1, "login-hash", "encrypted",
                1, Instant.now(), Instant.now(), Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200), Instant.now().plusSeconds(86_400), null);
        when(authService.listSessions(5L)).thenReturn(List.of(session));
        var principal = UsernamePasswordAuthenticationToken.authenticated(5L, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(get("/api/v1/auth/sessions").principal(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].device_label").value("Chrome on macOS"))
                .andExpect(jsonPath("$.data[0].sid").value("sid-1"))
                .andExpect(jsonPath("$.data[0].current").value(false))
                .andExpect(jsonPath("$.data[0].created_at").exists())
                .andExpect(jsonPath("$.data[0].last_refreshed_at").exists())
                .andExpect(jsonPath("$.data[0].refreshToken").doesNotExist());
    }

    @Test
    void revokeOwnedSessionReturnsNoContent() throws Exception {
        when(authService.revokeSession(5L, "sid-1")).thenReturn(true);
        var principal = UsernamePasswordAuthenticationToken.authenticated(5L, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(delete("/api/v1/auth/sessions/sid-1").principal(principal))
                .andExpect(status().isNoContent());

        verify(authService).revokeSession(5L, "sid-1");
    }
}
