package com.muse.meomuneum.map.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.map.catalog.MapZoneCatalog;
import com.muse.meomuneum.map.repository.MapDotRepository;

import tools.jackson.databind.ObjectMapper;

class MapDotControllerTests {
    private MockMvc mvc;
    private MapDotRepository repository;

    @BeforeEach
    void setUp() {
        MapZoneCatalog catalog = new MapZoneCatalog(new ObjectMapper());
        repository = org.mockito.Mockito.mock(MapDotRepository.class);
        when(repository.findLatestPublicRecords()).thenReturn(List.of());
        mvc = MockMvcBuilders.standaloneSetup(new MapDotController(catalog, repository, new ObjectMapper())).build();
    }

    @Test
    void returnsEveryMapDotInTheApiSpecificationShapeAndAnEtag() throws Exception {
        mvc.perform(get("/api/v1/map-dots")).andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.message").value("map dots retrieved"))
                .andExpect(jsonPath("$.data.items.length()").value(1050))
                .andExpect(jsonPath("$.data.items[0].map_dot_id").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("KR-COAST-0001"))
                .andExpect(jsonPath("$.data.items[0].album_cover_url").isEmpty())
                .andExpect(jsonPath("$.data.items[0].latest_recorded_at").isEmpty());
    }

    @Test
    void includesTheNewestPublicRecordMetadataForItsDot() throws Exception {
        when(repository.findLatestPublicRecords()).thenReturn(List.of(new MapDotRepository.LatestMapDotRecord(
                1, "https://cdn.example.com/album.jpg", Instant.parse("2026-09-03T11:00:00.123456Z"))));

        mvc.perform(get("/api/v1/map-dots")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].album_cover_url")
                        .value("https://cdn.example.com/album.jpg"))
                .andExpect(jsonPath("$.data.items[0].latest_recorded_at")
                        .value("2026-09-03T11:00:00.123456Z"));
    }

    @Test
    void returnsNotModifiedForTheCurrentEtag() throws Exception {
        var result = mvc.perform(get("/api/v1/map-dots")).andExpect(status().isOk()).andReturn();
        String etag = result.getResponse().getHeader("ETag");
        mvc.perform(get("/api/v1/map-dots").header("If-None-Match", etag))
                .andExpect(status().isNotModified()).andExpect(header().string("ETag", etag));
    }
}
