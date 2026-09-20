package com.muse.meomuneum.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtAuthenticationFilter;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.recommendation.controller.RecommendationController;
import com.muse.meomuneum.recommendation.dto.response.RecommendationResponse;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.recommendation.service.RecommendationService;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;

@WebMvcTest(value = RecommendationController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes",
        "recommendation.allow-guests=false"
})
@Import({SecurityConfig.class, RecommendationSecurityIntegrationTest.SecurityTestConfiguration.class})
class RecommendationSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RecommendationService recommendationService;

    @Autowired
    private CurrentUserResolver currentUserResolver;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        when(currentUserResolver.resolve(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
    }

    @Test
    void rejectsAnonymousRecommendationRequestWhenGuestsAreDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("unauthorized"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void doesNotRegisterJwtAuthenticationFilterAsServletFilterBean() {
        assertThat(applicationContext.getBeansOfType(JwtAuthenticationFilter.class)).isEmpty();
    }

    @Test
    void rejectsAnonymousRecommendationPostWhenGuestsAreDisabled() throws Exception {
        mockMvc.perform(post("/api/v1/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recommendationRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("unauthorized"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsInvalidBearerRecommendationPostWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/recommendations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recommendationRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("unauthorized"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void passesCsrfFilterForValidBearerRecommendationPost() throws Exception {
        when(recommendationService.create(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(1L)
        )).thenReturn(new RecommendationResponse(1L, "COMPLETED", "conversation-key", java.util.List.of(), null));

        mockMvc.perform(post("/api/v1/recommendations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + createAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recommendationRequest()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("recommendation completed"));
    }

    private String createAccessToken() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getRole()).thenReturn(UserRole.USER);
        return jwtTokenProvider.createAccessToken(user);
    }

    private String recommendationRequest() {
        return """
                {
                  "input_type": "TEXT",
                  "trigger_type": "CHATBOT",
                  "conversation_key": "11111111-1111-1111-1111-111111111111",
                  "prompt": "비 오는 밤에 들을 음악"
                }
                """;
    }

    @TestConfiguration
    static class SecurityTestConfiguration {

        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(new JwtProperties(
                    "project-api",
                    "project-api",
                    "development-only-secret-with-at-least-32-bytes",
                    3600,
                    1209600
            ));
        }

        @Bean
        SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper objectMapper) {
            return new SecurityErrorResponseWriter(objectMapper);
        }

        @Bean
        RecommendationService recommendationService() {
            return mock(RecommendationService.class);
        }

        @Bean
        CurrentUserResolver currentUserResolver() {
            return mock(CurrentUserResolver.class);
        }
    }
}
