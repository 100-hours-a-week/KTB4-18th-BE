package com.muse.meomuneum.location.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.location.dto.LocationResolveResponse;
import com.muse.meomuneum.location.dto.LocationResolveResponse.RegionSummary;
import com.muse.meomuneum.location.dto.LocationResolveResponse.RegionSummaryPair;
import com.muse.meomuneum.location.service.LocationResolutionService;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;

@WebMvcTest(value = LocationController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes", "recommendation.allow-guests=false"})
@Import({SecurityConfig.class, LocationSecurityIntegrationTest.SecurityTestConfiguration.class})
class LocationSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private LocationResolutionService locationResolutionService;

    @Test
    void rejectsAnonymousLocationResolution() throws Exception {
        mockMvc.perform(
                post("/api/v1/locations/resolve").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("unauthorized"));
    }

    @Test
    void acceptsAuthenticatedBearerRequestWithoutCsrfToken() throws Exception {
        when(locationResolutionService.resolve(any(), any())).thenReturn(response());

        mockMvc.perform(
                post("/api/v1/locations/resolve").header(HttpHeaders.AUTHORIZATION, "Bearer " + createAccessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("location resolved"));
    }

    private String createAccessToken() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getRole()).thenReturn(UserRole.USER);
        return jwtTokenProvider.createAccessToken(user);
    }

    private LocationResolveResponse response() {
        return new LocationResolveResponse(null,
                new RegionSummaryPair(new RegionSummary(9L, "41", "경기도"), new RegionSummary(25L, "41135", "성남시 분당구")),
                "loc_token", 300);
    }

    private String requestBody() {
        return """
                {"latitude":37.3595704,"longitude":127.105399,"accuracy_meters":18.5}
                """;
    }

    @TestConfiguration
    static class SecurityTestConfiguration {

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
        LocationResolutionService locationResolutionService() {
            return mock(LocationResolutionService.class);
        }
    }
}
