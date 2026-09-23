package com.muse.meomuneum.map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.config.JwtProperties;
import com.muse.meomuneum.global.config.SecurityConfig;
import com.muse.meomuneum.global.security.JwtTokenProvider;
import com.muse.meomuneum.global.security.SecurityErrorResponseWriter;
import com.muse.meomuneum.map.catalog.MapZoneCatalog;
import com.muse.meomuneum.map.controller.MapDotController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = MapDotController.class, properties = {
        "auth.jwt.secret=development-only-secret-with-at-least-32-bytes"
})
@Import({SecurityConfig.class, MapDotSecurityIntegrationTest.SecurityTestConfiguration.class})
class MapDotSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MapZoneCatalog catalog;

    @BeforeEach
    void setUp() {
        when(catalog.version()).thenReturn("2026-09-07");
        when(catalog.mapDots()).thenReturn(List.of(new MapZoneCatalog.MapDot(1L, "KR-COAST-0001")));
    }

    @Test
    void permitsAnonymousMapDotReadThroughTheSharedSecurityChain() throws Exception {
        mockMvc.perform(get("/api/v1/map-dots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("map dots retrieved"))
                .andExpect(jsonPath("$.data.items[0].code").value("KR-COAST-0001"));
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
        MapZoneCatalog mapZoneCatalog() {
            return mock(MapZoneCatalog.class);
        }
    }
}
