package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.musicrecord.controller.SongController;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Request;
import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Response;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;
import com.muse.meomuneum.musicrecord.service.SongRegistrationService;

class SongRegistrationHttpContractTest {
    private final SongRegistrationService service = mock(SongRegistrationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var users = mock(CurrentUserResolver.class);
        when(users.resolve(any())).thenReturn(1L);
        mvc = MockMvcBuilders.standaloneSetup(new SongController(service, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void returns201OnlyForInsertAnd200ForReuseWithExistingEnvelopeAndSnakeCaseMusic() throws Exception {
        var item = new MusicItem(42L, "ITUNES", "123", "song", "artist", null, null, null, false);
        when(service.register(new Request("123"))).thenReturn(new Response(item, true), new Response(item, false));
        var response = mvc.perform(post("/api/v1/songs").contentType("application/json")
                .content("{\"externalTrackId\":\"123\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("song registered"))
                .andExpect(jsonPath("$.data.music.music_id").value(42))
                .andExpect(jsonPath("$.data.music.external_music_id").value("123"))
                .andExpect(jsonPath("$.data.music.artist_name").value("artist"))
                .andExpect(jsonPath("$.data.created").value(true)).andReturn();
        assertThat(response.getResponse().getHeader("Location")).isNull();
        mvc.perform(post("/api/v1/songs").contentType("application/json")
                .content("{\"externalTrackId\":\"123\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.created").value(false));
    }

    @Test
    void rejectsNoncanonicalIdsAndUnknownClientMetadataBeforeService() throws Exception {
        for (String body : java.util.List.of("{}", "[]", "null", "{\"externalTrackId\":null}",
                "{\"externalTrackId\":123}", "{\"externalTrackId\":\"0\"}",
                "{\"externalTrackId\":\"01\"}", "{\"externalTrackId\":\"+1\"}",
                "{\"externalTrackId\":\" 1\"}", "{\"externalTrackId\":\"9223372036854775808\"}",
                "{\"externalTrackId\":\"123\",\"title\":\"untrusted\"}",
                "{\"externalTrackId\":\"123\",\"previewUrl\":\"untrusted\"}")) {
            mvc.perform(post("/api/v1/songs").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(service, never()).register(any());
    }
}
