package com.muse.meomuneum.recommendation;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.recommendation.controller.ImageRecommendationController;
import com.muse.meomuneum.recommendation.dto.response.RecommendationAcceptedResponse;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.recommendation.service.ImageRecommendationService;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;
import com.muse.meomuneum.user.service.UserAuthenticationService;

@WebMvcTest(value = ImageRecommendationController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes", "recommendation.allow-guests=true"})
@Import({SecurityConfig.class, CurrentUserResolver.class, ImageRecommendationSecurityIntegrationTest.Config.class})
class ImageRecommendationSecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider tokens;
    @Autowired
    private ImageRecommendationService service;

    @Test
    void guestsSettingDoesNotPermitAnonymousImageUpload() throws Exception {
        clearInvocations(service);
        mockMvc.perform(imageRequest()).andExpect(status().isUnauthorized());
        verify(service, never()).accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void bearerUserCanSubmitImageRequestWithoutSessionCsrf() throws Exception {
        when(service.accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1L)))
                .thenReturn(new RecommendationAcceptedResponse(42L, "PROCESSING",
                        "11111111-1111-1111-1111-111111111111"));
        mockMvc.perform(imageRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("recommendation processing"))
                .andExpect(jsonPath("$.data.recommendation_id").value(42))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.conversation_key").value("11111111-1111-1111-1111-111111111111"));
        verify(service).accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1L));
    }

    @Test
    void rejectsPromptFieldOnImageContract() throws Exception {
        clearInvocations(service);
        mockMvc.perform(imageRequest().param("prompt", "must not be accepted")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid image recommendation request"));
        verify(service, never()).accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMalformedConversationKeyBeforeServiceCall() throws Exception {
        clearInvocations(service);
        mockMvc.perform(imageRequest().param("conversation_key", "not-a-uuid")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid image recommendation request"));
        verify(service, never()).accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsUnexpectedInputTypeBeforeServiceCall() throws Exception {
        clearInvocations(service);
        mockMvc.perform(imageRequest().param("input_type", "VOICE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid image recommendation request"));
        verify(service, never()).accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsUnexpectedFilePartBeforeServiceCall() throws Exception {
        clearInvocations(service);
        mockMvc.perform(imageRequest().file(new MockMultipartFile("attachment", "extra.jpg", "image/jpeg",
                new byte[]{-1, -40, -1})).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid image recommendation request"));
        verify(service, never()).accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsExistingErrorEnvelopeWhenImagePartIsMissing() throws Exception {
        clearInvocations(service);
        mockMvc.perform(multipart("/api/v1/recommendations/images")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .param("conversation_key", "11111111-1111-1111-1111-111111111111")
                .param("input_type", "IMAGE")
                .param("trigger_type", "CHATBOT")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid image recommendation request"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        verify(service, never()).accept(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder imageRequest() {
        return multipart("/api/v1/recommendations/images")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .file(new MockMultipartFile("image", "photo.jpg", "image/jpeg", new byte[]{-1, -40, -1}))
                .param("conversation_key", "11111111-1111-1111-1111-111111111111")
                .param("input_type", "IMAGE")
                .param("trigger_type", "CHATBOT");
    }

    private String accessToken() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getRole()).thenReturn(UserRole.USER);
        return tokens.createAccessToken(user);
    }

    @TestConfiguration
    static class Config {
        @Bean
        JwtTokenProvider jwtTokenProvider() {
            return new JwtTokenProvider(new JwtProperties("project-api", "project-api",
                    "development-only-secret-with-at-least-32-bytes", 3600, 1209600));
        }

        @Bean
        SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper mapper) {
            return new SecurityErrorResponseWriter(mapper);
        }

        @Bean
        ImageRecommendationService imageRecommendationService() {
            return mock(ImageRecommendationService.class);
        }

        @Bean
        UserAuthenticationService userAuthenticationService() {
            UserAuthenticationService service = mock(UserAuthenticationService.class);
            when(service.findActiveUser(org.mockito.ArgumentMatchers.anyLong())).thenReturn(mock(User.class));
            return service;
        }
    }
}
