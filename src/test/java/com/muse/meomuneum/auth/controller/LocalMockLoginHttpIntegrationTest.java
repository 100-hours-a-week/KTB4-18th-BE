package com.muse.meomuneum.auth.controller;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.application.LocalMockLoginService;
import com.muse.meomuneum.auth.application.RefreshTokenCookieFactory;
import com.muse.meomuneum.auth.exception.LocalMockLoginExceptionHandler;
import com.muse.meomuneum.auth.exception.LocalMockLoginUnavailableException;
import com.muse.meomuneum.global.config.CorsConfig;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@WebMvcTest(value = LocalMockLoginController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes",
        "auth.cors.allowed-origins=http://localhost:5175",
        "auth.local-mock-login.enabled=true",
        "auth.local-mock-login.user-id=42"
})
@ActiveProfiles("local-mock")
@Import({SecurityConfig.class, CorsConfig.class, LocalMockLoginExceptionHandler.class,
        LocalMockLoginHttpIntegrationTest.LocalTestConfiguration.class})
class LocalMockLoginHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocalMockLoginService localMockLoginService;

    @MockitoBean
    private RefreshTokenCookieFactory refreshTokenCookieFactory;

    @BeforeEach
    void setUp() {
        when(localMockLoginService.issueToken()).thenReturn(
                new LocalMockLoginService.LocalMockLoginToken("test-access-token", 3600));
        doAnswer(invocation -> {
            HttpHeaders headers = invocation.getArgument(0);
            headers.add(HttpHeaders.SET_COOKIE, "refresh_token=; Path=/api/v1/auth; Max-Age=0; HttpOnly");
            return null;
        }).when(refreshTokenCookieFactory).deleteRefreshTokenCookie(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void validCsrfAndLoopbackOriginReturnsStandardLoginEnvelopeWithoutRefreshSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/local-mock-login")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "http://localhost:5175"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"message":"login success","data":{"access_token":"test-access-token","expires_in":3600}}
                        """));

        verify(localMockLoginService).issueToken();
    }

    @Test
    void missingCsrfAndDisallowedOriginAreNoStoreAndNeverIssueToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/local-mock-login")
                .header(HttpHeaders.ORIGIN, "http://localhost:5175"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        mockMvc.perform(post("/api/v1/auth/local-mock-login")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "https://attacker.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(localMockLoginService, never()).issueToken();
    }

    @Test
    void corsPreflightDenialIsNoStoreEvenWhenCorsDoesNotWriteAnErrorBody() throws Exception {
        mockMvc.perform(options("/api/v1/auth/local-mock-login")
                .header(HttpHeaders.ORIGIN, "https://attacker.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(localMockLoginService, never()).issueToken();
    }

    @Test
    void unavailableConfiguredUserReturnsGenericNoStoreFailure() throws Exception {
        when(localMockLoginService.issueToken()).thenThrow(new LocalMockLoginUnavailableException());

        mockMvc.perform(post("/api/v1/auth/local-mock-login")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "http://localhost:5175"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {"message":"LOCAL_MOCK_LOGIN_UNAVAILABLE","data":null}
                        """));
    }

    @Test
    void requestFromNonLoopbackPeerIsRejectedByEndpointGuard() throws Exception {
        mockMvc.perform(post("/api/v1/auth/local-mock-login")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "http://localhost:5175")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.10");
                    return request;
                }))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {"message":"LOCAL_MOCK_LOGIN_FORBIDDEN","data":null}
                        """));

        verify(localMockLoginService, never()).issueToken();
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
