package com.muse.meomuneum.recommendation;

import static org.mockito.Mockito.mock;
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
import com.muse.meomuneum.recommendation.controller.SpeechTranscriptionController;
import com.muse.meomuneum.recommendation.dto.response.SpeechTranscriptionResponse;
import com.muse.meomuneum.recommendation.service.SpeechTranscriptionService;
import com.muse.meomuneum.user.domain.User;
import com.muse.meomuneum.user.domain.UserRole;

@WebMvcTest(value = SpeechTranscriptionController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes",
        "recommendation.allow-guests=true"
})
@Import({SecurityConfig.class, SpeechTranscriptionSecurityIntegrationTest.SecurityTestConfiguration.class})
class SpeechTranscriptionSecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private SpeechTranscriptionService service;

    @Test
    void rejectsAnonymousRequestWhenRecommendationGuestsAreAllowed() throws Exception {
        mockMvc.perform(multipart("/api/v1/speech-transcriptions").file(audio()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("unauthorized"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void acceptsRequestWithValidBearerToken() throws Exception {
        when(service.transcribe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SpeechTranscriptionResponse("비 오는 날 듣기 좋은 노래"));

        mockMvc.perform(multipart("/api/v1/speech-transcriptions")
                        .file(audio())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + createAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transcript").value("비 오는 날 듣기 좋은 노래"));
    }

    private MockMultipartFile audio() {
        return new MockMultipartFile("audio", "voice.webm", "audio/webm", new byte[] {1});
    }

    private String createAccessToken() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getRole()).thenReturn(UserRole.USER);
        return jwtTokenProvider.createAccessToken(user);
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
        SpeechTranscriptionService speechTranscriptionService() {
            return mock(SpeechTranscriptionService.class);
        }
    }
}
