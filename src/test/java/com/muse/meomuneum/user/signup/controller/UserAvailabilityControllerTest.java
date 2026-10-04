package com.muse.meomuneum.user.signup.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.service.UserAuthenticationService;
import com.muse.meomuneum.user.signup.exception.UserAvailabilityExceptionHandler;
import com.muse.meomuneum.user.signup.repository.SignupRepository;
import com.muse.meomuneum.user.signup.service.AvailabilityRateLimiter;
import com.muse.meomuneum.user.signup.service.UserAvailabilityService;

@WebMvcTest(value = UserAvailabilityController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes",
        "auth.cors.allowed-origins=http://localhost:5174"})
@Import({SecurityConfig.class, UserAvailabilityExceptionHandler.class,
        UserAvailabilityControllerTest.AvailabilityTestConfiguration.class})
class UserAvailabilityControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SignupRepository signupRepository;

    @BeforeEach
    void setUp() {
        clearInvocations(signupRepository);
        when(signupRepository.existsUserByNickname("한글Ab12")).thenReturn(false);
        when(signupRepository.existsUserByEmail("qa@example.com")).thenReturn(true);
    }

    @Test
    void allowsAnonymousNicknameCheckAndDoesNotCacheTheResponse() throws Exception {
        mockMvc.perform(get("/api/v1/users/availability/nickname")
                .queryParam("value", "한글Ab12"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("availability checked"))
                .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    void reportsDuplicateEmail() throws Exception {
        mockMvc.perform(get("/api/v1/users/availability/email").queryParam("value", "QA@EXAMPLE.COM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    void rejectsInvalidInputWithoutQueryingUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users/availability/nickname").queryParam("value", "한 글"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid query parameter"));

        mockMvc.perform(get("/api/v1/users/availability/nickname"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid query parameter"));

        verify(signupRepository, never()).existsUserByNickname(anyString());
        verify(signupRepository, never()).existsUserByEmail(anyString());
    }

    @Test
    void rejectsEmailRejectedBySignupValidationWithoutQueryingUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users/availability/email").queryParam("value", "foo@bar..com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid query parameter"));

        verify(signupRepository, never()).existsUserByEmail(anyString());
    }

    @Test
    void limitsAnonymousRequestsPerClientAddress() throws Exception {
        for (int request = 0; request < 30; request++) {
            mockMvc.perform(get("/api/v1/users/availability/nickname")
                    .queryParam("value", "한글Ab12")
                    .with(requestBuilder -> {
                        requestBuilder.setRemoteAddr("192.0.2.10");
                        return requestBuilder;
                    })).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/users/availability/nickname")
                .queryParam("value", "한글Ab12")
                .with(requestBuilder -> {
                    requestBuilder.setRemoteAddr("192.0.2.10");
                    return requestBuilder;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("too many requests"));

        mockMvc.perform(get("/api/v1/users/availability/nickname")
                .queryParam("value", "한글Ab12")
                .with(requestBuilder -> {
                    requestBuilder.setRemoteAddr("192.0.2.11");
                    return requestBuilder;
                })).andExpect(status().isOk());
    }

    @TestConfiguration
    static class AvailabilityTestConfiguration {
        @Bean
        SignupRepository signupRepository() {
            return org.mockito.Mockito.mock(SignupRepository.class);
        }

        @Bean
        UserAvailabilityService userAvailabilityService(SignupRepository repository, Validator validator) {
            return new UserAvailabilityService(repository, validator);
        }

        @Bean
        AvailabilityRateLimiter availabilityRateLimiter() {
            return new AvailabilityRateLimiter();
        }

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(new com.muse.meomuneum.global.config.JwtProperties(
                    "project-api", "project-api", "development-only-secret-with-at-least-32-bytes", 3600, 1209600));
        }

        @Bean
        SecurityErrorResponseWriter securityErrorResponseWriter(com.fasterxml.jackson.databind.ObjectMapper mapper) {
            return new SecurityErrorResponseWriter(mapper);
        }

        @Bean
        UserAuthenticationService userAuthenticationService() {
            UserAuthenticationService service = org.mockito.Mockito.mock(UserAuthenticationService.class);
            when(service.findActiveUser(org.mockito.ArgumentMatchers.anyLong())).thenReturn(
                    org.mockito.Mockito.mock(User.class));
            return service;
        }
    }
}
