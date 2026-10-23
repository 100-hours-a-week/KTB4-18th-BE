package com.muse.meomuneum.auth.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.auth.exception.AuthStateConflictException;
import com.muse.meomuneum.auth.exception.AuthStateUnavailableException;
import com.muse.meomuneum.auth.service.HazelcastAuthStateStore;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@WebMvcTest(value = LoginQueueController.class, properties = {
        "auth.jwt.issuer=project-api",
        "auth.jwt.audience=project-api",
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes",
        "auth.jwt.access-token-expiration-seconds=3600",
        "auth.jwt.refresh-token-expiration-seconds=1209600",
        "auth.cors.allowed-origins=http://localhost:5173",
        "auth.cookie.secure=true",
        "auth.cookie.same-site=Lax"
})
@Import({SecurityConfig.class, LoginQueueSecurityIntegrationTest.LocalTestConfiguration.class})
class LoginQueueSecurityIntegrationTest {

    private static final String CURRENT_TICKET = "/api/v1/auth/login-queue/tickets/current";
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HazelcastAuthStateStore store;

    @Test
    void allowedOriginAndCsrfDeleteWithoutCookieNeedsNoAuthorizationAndExpiresQueueCookie() throws Exception {
        mockMvc.perform(delete(CURRENT_TICKET)
                .with(csrf())
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("MEO_QUEUE_V2="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"))));

        verify(store).cancelCurrentTicket(null);
    }

    @Test
    void allowedOriginAndCsrfDeleteTicketWithoutAuthorizationHeader() throws Exception {
        mockMvc.perform(delete(CURRENT_TICKET)
                .with(csrf())
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new Cookie("MEO_QUEUE_V2", "queue-cookie")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("MEO_QUEUE_V2="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"))));

        verify(store).cancelCurrentTicket("queue-cookie");
    }

    @Test
    void rejectsMissingOrInvalidCsrfBeforeCallingStore() throws Exception {
        mockMvc.perform(delete(CURRENT_TICKET).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(CURRENT_TICKET)
                .with(csrf().useInvalidToken())
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isForbidden());

        verify(store, never()).cancelCurrentTicket(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingOrHostileOriginBeforeCallingStore() throws Exception {
        mockMvc.perform(delete(CURRENT_TICKET).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(CURRENT_TICKET)
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "https://attacker.example"))
                .andExpect(status().isForbidden());

        verify(store, never()).cancelCurrentTicket(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void storeFailureReturnsGenericServerErrorWithoutExpiringCookie() throws Exception {
        doThrow(new AuthStateUnavailableException()).when(store).cancelCurrentTicket("queue-cookie");

        mockMvc.perform(delete(CURRENT_TICKET)
                .with(csrf())
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new Cookie("MEO_QUEUE_V2", "queue-cookie")))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void verifyingTicketConflictReturnsGenericServerErrorWithoutExpiringCookie() throws Exception {
        doThrow(new AuthStateConflictException("QUEUE_LOGIN_IN_PROGRESS"))
                .when(store).cancelCurrentTicket("queue-cookie");

        mockMvc.perform(delete(CURRENT_TICKET)
                .with(csrf())
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .cookie(new Cookie("MEO_QUEUE_V2", "queue-cookie")))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @TestConfiguration
    static class LocalTestConfiguration {

        @Bean
        JwtTokenProvider jwtTokenProvider(JwtProperties properties) {
            return new JwtTokenProvider(properties);
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
