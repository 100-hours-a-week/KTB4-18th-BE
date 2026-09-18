package com.muse.meomuneum.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.exception.AuthErrorCode;
import com.muse.meomuneum.auth.exception.AuthenticationFailedException;
import com.muse.meomuneum.auth.service.AuthService;
import com.muse.meomuneum.auth.service.CsrfTokenService;
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
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void loginReturnsSpecifiedResponseAndRefreshCookie() throws Exception {
        doAnswer(invocation -> {
            HttpHeaders headers = invocation.getArgument(2);
            headers.add(HttpHeaders.SET_COOKIE, "refresh_token=refresh-token; Path=/api/v1/auth");
            return new TokenResponse("access-token", 3600);
        }).when(authService).login(any(), any(HttpServletRequest.class), any(HttpHeaders.class));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        "refresh_token=refresh-token; Path=/api/v1/auth"))
                .andExpect(jsonPath("$.message").value("login success"))
                .andExpect(jsonPath("$.data.access_token").value("access-token"))
                .andExpect(jsonPath("$.data.expires_in").value(3600));
    }

    @Test
    void loginWithInvalidRequestReturnsSpecifiedError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid request"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void loginWithInvalidCredentialsReturnsSpecifiedError() throws Exception {
        when(authService.login(any(), any(HttpServletRequest.class), any(HttpHeaders.class)))
                .thenThrow(new AuthenticationFailedException(AuthErrorCode.LOGIN_INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"incorrect-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid credentials"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void csrfIssueReturnsSpecifiedResponse() throws Exception {
        when(csrfTokenService.issueToken(any(HttpServletRequest.class))).thenReturn("csrf-token");

        mockMvc.perform(get("/api/v1/auth/token/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("csrf token issued"))
                .andExpect(jsonPath("$.data.csrf_token").value("csrf-token"));
    }
}
