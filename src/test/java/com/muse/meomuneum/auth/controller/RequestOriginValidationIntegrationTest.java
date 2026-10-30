package com.muse.meomuneum.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.application.AuthFlowService;
import com.muse.meomuneum.auth.application.AuthFlowService.FlowSnapshot;
import com.muse.meomuneum.auth.application.AuthFlowService.LogoutReceipt;
import com.muse.meomuneum.auth.application.AuthService;
import com.muse.meomuneum.auth.application.CsrfTokenService;
import com.muse.meomuneum.auth.application.QueuedLoginService.LoginResult;
import com.muse.meomuneum.auth.application.RefreshReplayCipher;
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@WebMvcTest(value = AuthController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes",
        "auth.cors.allowed-origins=http://localhost:5173"
})
@Import({SecurityConfig.class, RequestOriginValidationIntegrationTest.LocalTestConfiguration.class})
class RequestOriginValidationIntegrationTest {

    @MockitoBean
    private AuthFlowService authFlowService;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CsrfTokenService csrfTokenService;

    @MockitoBean
    private RefreshReplayCipher replayCipher;

    @BeforeEach
    void configureFlowBoundary() {
        doAnswer(invocation -> {
            AuthFlowService.FlowMutation<?> mutation = invocation.getArgument(1);
            HttpServletRequest request = invocation.getArgument(0);
            boolean authenticated = request.getRequestURI().endsWith("/token/refresh");
            return mutation.apply(new FlowSnapshot("123e4567-e89b-42d3-a456-426614174000",
                    1, 1, authenticated ? "AUTHENTICATED" : "ANONYMOUS", authenticated,
                    null, authenticated ? "test-session" : null, "EXISTING"));
        }).when(authFlowService).withLockedFlow(any(), any());
        when(authFlowService.commitLogout(any(), any(), anyString())).thenReturn(
                new LogoutReceipt("123e4567-e89b-42d3-a456-426614174001", true, 2, 2, Instant.now()));
    }

    @Test
    void rejectsLoginFromHostileOriginBeforeIssuingRefreshCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "https://attacker.example")
                .header(HttpHeaders.REFERER, "http://localhost:5173/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\",\"password\":\"Testpass1!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(authService, never()).login(any(LoginRequest.class), any(HttpServletRequest.class),
                any(HttpHeaders.class));
    }

    @Test
    void rejectsLoginWhenOriginAndRefererAreMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\",\"password\":\"Testpass1!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(authService, never()).login(any(LoginRequest.class), any(HttpServletRequest.class),
                any(HttpHeaders.class));
    }

    @Test
    void acceptsConfiguredOriginForLoginAndKeepsCookieResponseContract() throws Exception {
        when(authService.loginWithTicket(any(LoginRequest.class), any(HttpServletRequest.class),
                any(HttpHeaders.class)))
                .thenAnswer(invocation -> {
                    HttpHeaders headers = invocation.getArgument(2);
                    headers.add(HttpHeaders.SET_COOKIE, "refresh_token=test-only; Path=/api/v1/auth; HttpOnly");
                    return new LoginResult(new TokenResponse("test-access", 3600), "queue-ticket-id");
                });

        mockMvc.perform(post("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header("X-Auth-Flow-Id", "123e4567-e89b-42d3-a456-426614174000")
                .header("X-Auth-Flow-Epoch", "1")
                .header("Idempotency-Key", "123e4567-e89b-42d3-a456-426614174001")
                .cookie(new Cookie("MEO_AUTH_FLOW", "flow-cookie"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\",\"password\":\"Testpass1!\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth")));

        verify(authService).loginWithTicket(any(LoginRequest.class), any(HttpServletRequest.class),
                any(HttpHeaders.class));
    }

    @Test
    void rejectsRefreshAndLogoutFromHostileOriginBeforeAuthServiceRuns() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/refresh")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "https://attacker.example")
                .header(HttpHeaders.REFERER, "http://localhost:5173/chatbot")
                .cookie(new Cookie("refresh_token", "test-only")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/logout")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "https://attacker.example")
                .header(HttpHeaders.REFERER, "http://localhost:5173/my"))
                .andExpect(status().isForbidden());

        verify(authService, never()).refresh(any(HttpServletRequest.class), any(HttpHeaders.class));
        verify(authService, never()).logout(any(HttpServletRequest.class), any(HttpHeaders.class));
    }

    @Test
    void rejectsRefreshAndLogoutWhenBothOriginAndRefererAreMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token/refresh")
                .with(csrf())
                .cookie(new Cookie("refresh_token", "test-only")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/logout").with(csrf()))
                .andExpect(status().isForbidden());

        verify(authService, never()).refresh(any(HttpServletRequest.class), any(HttpHeaders.class));
        verify(authService, never()).logout(any(HttpServletRequest.class), any(HttpHeaders.class));
    }

    @Test
    void acceptsConfiguredOriginForRefreshAndLogout() throws Exception {
        when(authService.refresh(any(HttpServletRequest.class), any(HttpHeaders.class)))
                .thenReturn(new TokenResponse("test-access", 3600));

        mockMvc.perform(post("/api/v1/auth/token/refresh")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header("X-Auth-Flow-Id", "123e4567-e89b-42d3-a456-426614174000")
                .header("X-Auth-Flow-Epoch", "1")
                .cookie(new Cookie("MEO_AUTH_FLOW", "flow-cookie"))
                .cookie(new Cookie("refresh_token", "test-only")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/logout")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header("X-Auth-Flow-Id", "123e4567-e89b-42d3-a456-426614174000")
                .header("X-Auth-Flow-Epoch", "1")
                .header("Idempotency-Key", "123e4567-e89b-42d3-a456-426614174001")
                .cookie(new Cookie("MEO_AUTH_FLOW", "flow-cookie")))
                .andExpect(status().isOk());

        verify(authService).refresh(any(HttpServletRequest.class), any(HttpHeaders.class));
        verify(authService).logout(any(HttpServletRequest.class), any(HttpHeaders.class));
    }

    @TestConfiguration
    static class LocalTestConfiguration {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            JwtTokenProvider provider = mock(JwtTokenProvider.class);
            when(provider.parseAccessToken("test-access")).thenReturn(new TokenClaims(42L, List.of("USER"),
                    Instant.now().plusSeconds(3600), "test-session", "test-jti"));
            return provider;
        }

        @Bean
        SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper objectMapper) {
            return new SecurityErrorResponseWriter(objectMapper);
        }

        @Bean
        UserAuthenticationService userAuthenticationService() {
            return org.mockito.Mockito.mock(UserAuthenticationService.class);
        }
    }
}
