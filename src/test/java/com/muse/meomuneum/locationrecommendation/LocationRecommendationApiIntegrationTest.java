package com.muse.meomuneum.locationrecommendation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.locationrecommendation.controller.LocationRecommendationController;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.CancellationRequest;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.CancellationResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.ContextResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.DisplayClaimResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationRequest;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RecommendationResponse;
import com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.RegionResponse;
import com.muse.meomuneum.locationrecommendation.exception.LocationRecommendationExceptionHandler;
import com.muse.meomuneum.locationrecommendation.service.LocationRecommendationService;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;

class LocationRecommendationApiIntegrationTest {
    private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID GENERATION = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private MockMvc mvc;
    private LocationRecommendationService service;

    @BeforeEach
    void setUp() {
        service = mock(LocationRecommendationService.class);
        CurrentUserResolver users = mock(CurrentUserResolver.class);
        when(users.resolve(nullable(Authentication.class))).thenReturn(9L);
        mvc = MockMvcBuilders.standaloneSetup(new LocationRecommendationController(service, users))
                .setControllerAdvice(new LocationRecommendationExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void contextIsAuthenticatedControllerOwnedAndNoStore() throws Exception {
        when(service.context(9L)).thenReturn(new ContextResponse(GENERATION, 4, "2026-10-31", null));

        mvc.perform(get("/api/v1/location-recommendation-context"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.message").value("recommendation context"))
                .andExpect(jsonPath("$.data.scopeGeneration").value(GENERATION.toString()))
                .andExpect(jsonPath("$.data.cancelEpoch").value(4));
        verify(service).context(9L);
    }

    @Test
    void recommendationAcceptsOnlyDocumentedBodyAndUuidV4Header() throws Exception {
        var response = new RecommendationResponse(REQUEST_ID, "EMPTY", null,
                new RegionResponse(123, "11230", "Dongdaemun-gu", "Seoul"), List.of(), GENERATION, 4, 0);
        when(service.recommend(eq(9L), eq(REQUEST_ID), any(RecommendationRequest.class))).thenReturn(response);
        String body = """
                {"latitude":37.5744,"longitude":127.0395,"accuracyMeters":50,"sampleAgeMs":120,
                "trigger":"SESSION_START","scopeGeneration":"11111111-1111-4111-8111-111111111111",
                "cancelEpoch":4}
                """;

        mvc.perform(post("/api/v1/location-recommendations").header("Idempotency-Key", REQUEST_ID.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("location recommendation checked"))
                .andExpect(jsonPath("$.data.status").value("EMPTY"))
                .andExpect(jsonPath("$.data.tracks").isEmpty());

        verify(service).recommend(eq(9L), eq(REQUEST_ID), any(RecommendationRequest.class));
    }

    @Test
    void rejectsMalformedOrMissingIdempotencyHeaderAndUnknownBodyFields() throws Exception {
        String validBody = """
                {"latitude":37.5,"longitude":127,"accuracyMeters":0,"sampleAgeMs":0,
                "trigger":"RESUME","scopeGeneration":"11111111-1111-4111-8111-111111111111",
                "cancelEpoch":0}
                """;
        mvc.perform(post("/api/v1/location-recommendations").contentType(MediaType.APPLICATION_JSON)
                .content(validBody)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(post("/api/v1/location-recommendations").header("Idempotency-Key",
                "11111111-1111-1111-8111-111111111111").contentType(MediaType.APPLICATION_JSON)
                .content(validBody)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/location-recommendations").header("Idempotency-Key", REQUEST_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody.substring(0, validBody.length() - 1) + ",\"extra\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
        verify(service, never()).recommend(anyLong(), any(), any());
    }

    @Test
    void rejectsPollTriggerAndReturnsStableUnsupportedMediaTypeEnvelope() throws Exception {
        String pollBody = """
                {"latitude":37.5,"longitude":127,"accuracyMeters":0,"sampleAgeMs":0,
                "trigger":"POLL","scopeGeneration":"11111111-1111-4111-8111-111111111111",
                "cancelEpoch":0}
                """;
        var response = new RecommendationResponse(REQUEST_ID, "EMPTY", null,
                new RegionResponse(123, "11230", "Dongdaemun-gu", "Seoul"), List.of(), GENERATION, 4, 0);
        when(service.recommend(eq(9L), eq(REQUEST_ID), any(RecommendationRequest.class))).thenReturn(response);

        mvc.perform(post("/api/v1/location-recommendations").header("Idempotency-Key", REQUEST_ID.toString())
                .contentType(MediaType.TEXT_PLAIN).content(pollBody))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(service, never()).recommend(anyLong(), any(), any());

        mvc.perform(post("/api/v1/location-recommendations").header("Idempotency-Key", REQUEST_ID.toString())
                .contentType(MediaType.APPLICATION_JSON).content(pollBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
        verify(service, never()).recommend(anyLong(), any(), any());
    }

    @Test
    void canonicalStatusDisplayClaimAndCancellationUseTheDocumentedRoutes() throws Exception {
        UUID claimKey = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID cancelKey = UUID.fromString("44444444-4444-4444-8444-444444444444");
        when(service.get(9L, REQUEST_ID)).thenReturn(new RecommendationResponse(REQUEST_ID, "PROCESSING", null,
                null, List.of(), GENERATION, 4, 0));
        when(service.claim(eq(9L), eq(REQUEST_ID), eq(claimKey), any()))
                .thenReturn(new DisplayClaimResponse(REQUEST_ID, true, null));
        when(service.cancel(eq(9L), eq(cancelKey), any(CancellationRequest.class)))
                .thenReturn(new CancellationResponse("CANCELLED", GENERATION, 5));

        mvc.perform(get("/api/v1/location-recommendations/{requestId}", REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.requestId").value(REQUEST_ID.toString()));
        mvc.perform(post("/api/v1/location-recommendations/{requestId}/display-claims", REQUEST_ID)
                .header("Idempotency-Key", claimKey.toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopeGeneration\":\"" + GENERATION + "\",\"cancelEpoch\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayAllowed").value(true));
        mvc.perform(post("/api/v1/location-recommendation-context/cancellations")
                .header("Idempotency-Key", cancelKey.toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + REQUEST_ID + "\",\"scopeGeneration\":\"" + GENERATION
                        + "\",\"cancelEpoch\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelEpoch").value(5));
    }
}
