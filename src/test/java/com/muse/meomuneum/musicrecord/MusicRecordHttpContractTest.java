package com.muse.meomuneum.musicrecord;

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.musicrecord.controller.MusicRecordController;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.provider.KakaoReverseGeocodingClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;
import com.muse.meomuneum.musicrecord.service.LocationTokenService;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;

class MusicRecordHttpContractTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MusicRecordService service = new MusicRecordService(mock(MusicRecordRepository.class),
                mock(ItunesMusicSearchClient.class), mock(LocationTokenService.class),
                mock(KakaoReverseGeocodingClient.class), new MusicSearchCursorCodec("test-only-secret"));
        CurrentUserResolver users = mock(CurrentUserResolver.class);
        when(users.resolve(nullable(Authentication.class))).thenReturn(1L);
        mvc = MockMvcBuilders.standaloneSetup(new MusicRecordController(service, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void accuracyAboveOneHundredMetersUsesLocationContractError() throws Exception {
        mvc.perform(post("/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":37.5,\"longitude\":127.0,\"accuracy_meters\":101}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "invalid coordinates or location accuracy insufficient"));
    }

    @Test
    void malformedSearchSizeUsesSingleDocumentedSearchMessage() throws Exception {
        mvc.perform(get("/api/v1/music/search")
                        .param("query", "밤")
                        .param("provider", "ITUNES")
                        .param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("search query required"));
    }

    @Test
    void invalidLocationBodyUsesLocationContractError() throws Exception {
        mvc.perform(post("/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":101,\"longitude\":127,\"accuracy_meters\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "invalid coordinates or location accuracy insufficient"));
    }
}
