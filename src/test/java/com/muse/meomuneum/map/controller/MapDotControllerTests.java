package com.muse.meomuneum.map.controller;

import com.muse.meomuneum.map.catalog.MapZoneCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MapDotControllerTests {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MapZoneCatalog catalog = new MapZoneCatalog(new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new MapDotController(catalog)).build();
    }

    @Test
    void returnsEveryMapDotInTheApiSpecificationShapeAndAnEtag() throws Exception {
        mvc.perform(get("/api/v1/map-dots"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2026-09-07\""))
                .andExpect(jsonPath("$.message").value("map dots retrieved"))
                .andExpect(jsonPath("$.data.items.length()").value(1050))
                .andExpect(jsonPath("$.data.items[0].map_dot_id").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("KR-COAST-0001"))
                .andExpect(jsonPath("$.data.items[0].album_cover_url").isEmpty())
                .andExpect(jsonPath("$.data.items[0].latest_recorded_at").isEmpty());
    }

    @Test
    void returnsNotModifiedForTheCurrentEtag() throws Exception {
        mvc.perform(get("/api/v1/map-dots").header("If-None-Match", "\"2026-09-07\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"2026-09-07\""));
    }
}
