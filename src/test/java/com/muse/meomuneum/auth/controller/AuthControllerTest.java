package com.muse.meomuneum.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.application.AuthService;
import com.muse.meomuneum.auth.application.CsrfTokenService;
import com.muse.meomuneum.auth.exception.AuthExceptionHandler;
import com.muse.meomuneum.auth.exception.AuthFlowException;
import com.muse.meomuneum.auth.exception.AuthFlowExceptionHandler;
import com.muse.meomuneum.global.exception.GlobalExceptionHandler;

class AuthControllerTest {

    private AuthService authService;
    private CsrfTokenService csrfTokenService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        csrfTokenService = mock(CsrfTokenService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, csrfTokenService))
                .setControllerAdvice(new AuthExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @Test
    void markerlessLoginIsRejectedWithoutCallingLegacyAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.message").value("INVALID_LOGIN_PROTOCOL"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(authService);
    }

    @Test
    void loginWithInvalidRequestReturnsSpecifiedError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"invalid-email\",\"password\":\"\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid request")).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void loginWithMalformedJsonReturnsSpecifiedError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("invalid request"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void csrfIssueReturnsSpecifiedResponse() throws Exception {
        when(csrfTokenService.issueToken(any(HttpServletRequest.class), any(HttpServletResponse.class)))
                .thenReturn("csrf-token");

        mockMvc.perform(get("/api/v1/auth/token/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("csrf token issued"))
                .andExpect(jsonPath("$.data.csrf_token").value("csrf-token"));
    }

    @Test
    void logoutPassesRequestAndReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());

        verify(authService).logout(any(HttpServletRequest.class), any(HttpHeaders.class));
    }

    @Test
    void supersededLogoutDoesNotExpireCookiesOrRunLocalCleanup() throws Exception {
        AuthFlowService flowService = mock(AuthFlowService.class);
        when(flowService.commitLogout(any(HttpServletRequest.class), any(HttpHeaders.class), any()))
                .thenThrow(new AuthFlowException("AUTH_FLOW_EPOCH_STALE"));
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, csrfTokenService, flowService,
                mock(com.muse.meomuneum.auth.application.RefreshReplayCipher.class)))
                .setControllerAdvice(new AuthExceptionHandler(), new AuthFlowExceptionHandler(),
                        new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/auth/logout").header("Idempotency-Key",
                "123e4567-e89b-42d3-a456-426614174000"))
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(authService, never()).logout(any(HttpServletRequest.class), any(HttpHeaders.class));
        verify(flowService, never()).expireAnonymousCookies(any(HttpHeaders.class));
    }

    @Test
    void legacyLoginRecoveryRouteIsNotRegistered() throws Exception {
        assertThat(java.util.Arrays.stream(AuthController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class))
                .map(method -> method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class))
                .flatMap(mapping -> java.util.Arrays.stream(mapping.value()))
                .noneMatch("/login/recovery"::equals)).isTrue();
    }
}
