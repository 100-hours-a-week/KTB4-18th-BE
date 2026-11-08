package com.muse.meomuneum.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.global.security.TokenClaims;
import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.recommendation.controller.RecommendationController;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.exception.RecommendationExceptionHandler;
import com.muse.meomuneum.recommendation.provider.RecommendationProvider;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;
import com.muse.meomuneum.recommendation.service.RecommendationEventStream;
import com.muse.meomuneum.recommendation.service.RecommendationLocationContext;
import com.muse.meomuneum.recommendation.service.RecommendationService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RecommendationLocationAdmissionTest {
    @Test
    void publicEndpointUsesAuthenticatedSidAndPersistsOnlyVerifiedIds() throws Exception {
        var repository = mock(RecommendationRepository.class);
        var context = mock(RecommendationLocationContext.class);
        var service = new RecommendationService(mock(RecommendationProvider.class), repository,
                new SimpleMeterRegistry(), mock(RecommendationEventStream.class), task -> {
                }, context);
        var mvc = MockMvcBuilders.standaloneSetup(new RecommendationController(service, new CurrentUserResolver()))
                .setControllerAdvice(new RecommendationExceptionHandler()).build();
        String sid = UUID.randomUUID().toString();
        var auth = UsernamePasswordAuthenticationToken.authenticated(1L, null, List.of());
        auth.setDetails(new TokenClaims(1L, List.of("USER"), Instant.now().plusSeconds(60), sid, "jti"));
        when(context.resolve("signed-location", 1L, sid)).thenReturn(new LocationResolutionClaims(1L,
                2L, "parent", 3L, "region", 4L, Instant.now().plusSeconds(60), sid));
        when(repository.createSession(any(), isNull(), eq(1L), any(), eq(3L), eq(4L))).thenReturn(10L);
        mvc.perform(post("/api/v1/recommendations").principal(auth).contentType("application/json")
                .content(body(",\"location_resolution_token\":\"signed-location\",\"region_id\":999")))
                .andExpect(status().isAccepted());
        verify(repository).createSession(any(), isNull(), eq(1L), any(), eq(3L), eq(4L));
        verify(repository, never()).createSession(any(), any(), any(), any());
        when(context.resolve("invalid", 1L, sid)).thenThrow(new RecommendationException(400, "invalid token"));
        mvc.perform(post("/api/v1/recommendations").principal(auth).contentType("application/json")
                .content(body(",\"location_resolution_token\":\"invalid\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void omittedAndNullLocationKeepLegacyGuestAdmission() throws Exception {
        var repository = mock(RecommendationRepository.class);
        var context = mock(RecommendationLocationContext.class);
        var service = new RecommendationService(mock(RecommendationProvider.class), repository,
                new SimpleMeterRegistry(), mock(RecommendationEventStream.class), task -> {
                }, context);
        var mvc = MockMvcBuilders.standaloneSetup(new RecommendationController(service, new CurrentUserResolver()))
                .setControllerAdvice(new RecommendationExceptionHandler()).build();
        when(repository.createSession(any(), any(), isNull(), any())).thenReturn(10L);
        for (String extra : List.of("", ",\"location_resolution_token\":null")) {
            var result = mvc.perform(post("/api/v1/recommendations").contentType("application/json")
                    .content(body(extra))).andExpect(status().isAccepted()).andReturn();
            assertThat(result.getResponse().getContentAsString()).contains("PROCESSING");
        }
        verify(context, never()).resolve(any(), any(), any());
        verify(repository, never()).createSession(any(), any(), any(), any(), any(), any());
    }

    private String body(String extra) {
        return """
                {"input_type":"VOICE","trigger_type":"CHATBOT", "conversation_key":"%s","prompt":"test"%s}
                """.formatted(UUID.randomUUID(), extra);
    }
}
