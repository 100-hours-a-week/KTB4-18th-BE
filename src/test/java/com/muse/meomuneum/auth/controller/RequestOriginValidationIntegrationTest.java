package com.muse.meomuneum.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

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
import com.muse.meomuneum.auth.dto.LoginRequest;
import com.muse.meomuneum.auth.dto.TokenResponse;
import com.muse.meomuneum.auth.service.AuthService;
import com.muse.meomuneum.auth.service.CsrfTokenService;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@WebMvcTest(value = AuthController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes",
        "auth.cors.allowed-origins=http://localhost:5173"
})
@Import({SecurityConfig.class, RequestOriginValidationIntegrationTest.LocalTestConfiguration.class})
class RequestOriginValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CsrfTokenService csrfTokenService;

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
        when(authService.login(any(LoginRequest.class), any(HttpServletRequest.class), any(HttpHeaders.class)))
                .thenAnswer(invocation -> {
                    HttpHeaders headers = invocation.getArgument(2);
                    headers.add(HttpHeaders.SET_COOKIE, "refresh_token=test-only; Path=/api/v1/auth; HttpOnly");
                    return new TokenResponse("test-access", 3600);
                });

        mockMvc.perform(post("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"test@example.com\",\"password\":\"Testpass1!\"}"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth")));

        verify(authService).login(any(LoginRequest.class), any(HttpServletRequest.class), any(HttpHeaders.class));
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
                .cookie(new Cookie("refresh_token", "test-only")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/logout")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isNoContent());

        verify(authService).refresh(any(HttpServletRequest.class), any(HttpHeaders.class));
        verify(authService).logout(any(HttpServletRequest.class), any(HttpHeaders.class));
    }

    @TestConfiguration
    static class LocalTestConfiguration {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(new JwtProperties("project-api", "project-api",
                    "development-only-secret-with-at-least-32-bytes", 3600, 1209600));
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
