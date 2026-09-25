package com.muse.meomuneum.location.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.location.dto.LocationResolveResponse;
import com.muse.meomuneum.location.dto.LocationResolveResponse.RegionSummary;
import com.muse.meomuneum.location.dto.LocationResolveResponse.RegionSummaryPair;
import com.muse.meomuneum.location.exception.LocationErrorCode;
import com.muse.meomuneum.location.exception.LocationException;
import com.muse.meomuneum.location.exception.LocationExceptionHandler;
import com.muse.meomuneum.location.service.LocationResolutionService;

class LocationControllerTest {

    private LocationResolutionService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(LocationResolutionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new LocationController(service))
                .setControllerAdvice(new LocationExceptionHandler(), new GlobalExceptionHandler()).build();
    }

    @Test
    void returnsSnakeCaseLocationResolutionWithoutOriginalCoordinates() throws Exception {
        LocationResolveResponse response = new LocationResolveResponse(null,
                new RegionSummaryPair(new RegionSummary(9L, "41", "경기도"), new RegionSummary(25L, "41135", "성남시 분당구")),
                "loc_token", 300);
        when(service.resolve(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/locations/resolve").principal(new UsernamePasswordAuthenticationToken(7L, null))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "latitude": 37.3595704,
                          "longitude": 127.105399,
                          "accuracy_meters": 18.5
                        }
                        """)).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("location resolved"))
                .andExpect(jsonPath("$.data.map_dot").doesNotExist())
                .andExpect(jsonPath("$.data.region.sido.region_id").value(9))
                .andExpect(jsonPath("$.data.region.sigungu.region_id").value(25))
                .andExpect(jsonPath("$.data.location_resolution_token").value("loc_token"))
                .andExpect(jsonPath("$.data.expires_in").value(300))
                .andExpect(jsonPath("$.data.latitude").doesNotExist())
                .andExpect(jsonPath("$.data.longitude").doesNotExist());
    }

    @Test
    void returnsSpecifiedErrorForInsufficientAccuracy() throws Exception {
        when(service.resolve(any(), any())).thenThrow(new LocationException(LocationErrorCode.INVALID_COORDINATES));

        mockMvc.perform(post("/api/v1/locations/resolve").principal(new UsernamePasswordAuthenticationToken(7L, null))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"latitude":37.0,"longitude":127.0,"accuracy_meters":101.0}
                        """)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid coordinates or location accuracy insufficient"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void returnsInvalidCoordinatesForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/locations/resolve").principal(new UsernamePasswordAuthenticationToken(7L, null))
                .contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid coordinates or location accuracy insufficient"));
    }

    @Test
    void returnsSpecifiedErrorWhenReverseGeocodingFails() throws Exception {
        when(service.resolve(any(), any()))
                .thenThrow(new LocationException(LocationErrorCode.REVERSE_GEOCODING_FAILED));

        mockMvc.perform(post("/api/v1/locations/resolve").principal(new UsernamePasswordAuthenticationToken(7L, null))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"latitude":37.0,"longitude":127.0,"accuracy_meters":10.0}
                        """)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("reverse geocoding failed"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
