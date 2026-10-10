package com.muse.meomuneum.musicrecord;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.musicrecord.controller.MusicRecordController;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;
import com.muse.meomuneum.musicrecord.service.MusicSearchStorageService;

class MusicRecordHttpContractTest {
    private MockMvc mvc;
    private MusicRecordRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(MusicRecordRepository.class);
        MusicRecordService service = new MusicRecordService(repository,
                mock(ItunesMusicSearchClient.class), mock(LocationResolutionTokenProvider.class),
                new MusicSearchCursorCodec("test-only-secret"), mock(MusicSearchStorageService.class));
        CurrentUserResolver users = mock(CurrentUserResolver.class);
        when(users.resolve(nullable(Authentication.class))).thenReturn(1L);
        mvc = MockMvcBuilders.standaloneSetup(new MusicRecordController(service, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void bulkDeleteAcceptsIdsAndReturnsEmpty204() throws Exception {
        when(repository.deleteRecord(eq(1L), eq(7L), any(Instant.class))).thenReturn(1);
        mvc.perform(delete("/api/v1/music-records")
                .contentType("application/json").content("{\"record_ids\":[7,7]}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void bulkDeleteRejectsInvalidBodies() throws Exception {
        for (String body : java.util.List.of("{}", "{\"record_ids\":[]}",
                "{\"record_ids\":[null]}", "{\"record_ids\":[0]}", "{\"record_ids\":[-1]}")) {
            mvc.perform(delete("/api/v1/music-records").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(delete("/api/v1/music-records").contentType("application/json")
                .content("{\"record_ids\":" + java.util.Collections.nCopies(101, 7L) + "}"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(repository);
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
    void deleteReturnsNoContentWithoutAJsonBody() throws Exception {
        when(repository.deleteRecord(eq(1L), eq(7L), any(Instant.class))).thenReturn(1);
        mvc.perform(delete("/api/v1/music-records/7"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void deleteUsesDocumentedForbiddenResponse() throws Exception {
        when(repository.existsActiveRecord(7L)).thenReturn(true);
        mvc.perform(delete("/api/v1/music-records/7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("forbidden"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void missingDeleteUsesDocumentedNotFoundResponse() throws Exception {
        mvc.perform(delete("/api/v1/music-records/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("music record not found"));
    }

    @Test
    void malformedJsonBodyUsesBadRequestEnvelope() throws Exception {
        mvc.perform(patch("/api/v1/music-records/7").contentType("application/json")
                .content("{\"music\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid request"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void nonOwnerGetsForbiddenBeforeStructurallyInvalidPatchIsValidated() throws Exception {
        when(repository.existsActiveRecord(7L)).thenReturn(true);

        mvc.perform(patch("/api/v1/music-records/7").contentType("application/json")
                .content("{\"unexpected\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("forbidden"))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(repository, never()).findRecordMusicIdentity(1L, 7L);
    }

}
