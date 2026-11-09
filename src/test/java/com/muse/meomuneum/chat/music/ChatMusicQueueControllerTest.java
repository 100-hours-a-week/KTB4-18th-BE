package com.muse.meomuneum.chat.music;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.global.security.TokenClaims;

class ChatMusicQueueControllerTest {
    private static final String SID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VIEW = "550e8400-e29b-41d4-a716-446655440001";
    private static final String COMMAND = "550e8400-e29b-41d4-a716-446655440002";
    private static final String ROOT = "/api/v1/chat-rooms/7";
    private ChatMusicQueueService service;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken auth;
    private ChatMusicQueueService.Caller caller;

    @BeforeEach
    void setup() {
        service = mock(ChatMusicQueueService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChatMusicQueueController(service))
                .setControllerAdvice(new ChatMusicExceptionHandler()).build();
        auth = UsernamePasswordAuthenticationToken.authenticated(1L, null, List.of());
        auth.setDetails(new TokenClaims(1L, List.of("USER"), Instant.now().plusSeconds(60), SID, "test"));
        caller = new ChatMusicQueueService.Caller(1, SID, 7, 3, VIEW);
    }

    @Test
    void exposesBothGetContractsWithNoStoreAndSnakeCaseEnvelope() throws Exception {
        when(service.candidates(caller, "song", 20, null)).thenReturn(Map.of("items", List.of()));
        when(service.queue(caller, 20, null)).thenReturn(Map.of("items", List.of(), "version", 2));
        mvc.perform(get(ROOT + "/requestable-music").principal(auth).param("membership_id", "3")
                .param("view_session_id", VIEW).param("query", "song"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items").isArray());
        mvc.perform(get(ROOT + "/queue-items").principal(auth).param("membership_id", "3")
                .param("view_session_id", VIEW)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void addReturnsOriginalStoredJsonAndCancelReturnsEmpty204() throws Exception {
        when(service.add(caller, COMMAND, 11)).thenReturn(new ChatMusicQueueService.Result(201,
                "{\"message\":\"music queued\",\"data\":{\"queue_item_id\":12,\"version\":1}}"));
        when(service.cancel(caller, COMMAND, 12, 1)).thenReturn(new ChatMusicQueueService.Result(204, "{}"));
        mvc.perform(post(ROOT + "/queue-items").principal(auth).param("membership_id", "3")
                .param("view_session_id", VIEW).header("Idempotency-Key", COMMAND)
                .contentType(MediaType.APPLICATION_JSON).content("{\"music_id\":11}"))
                .andExpect(status().isCreated()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.queue_item_id").value(12));
        mvc.perform(delete(ROOT + "/queue-items/12").principal(auth).param("membership_id", "3")
                .param("view_session_id", VIEW).param("expected_version", "1").header("Idempotency-Key", COMMAND))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void rejectsMissingHeaderDuplicateUnknownAndNonIntegerMusicIds() throws Exception {
        for (String body : List.of("{\"music_id\":11,\"music_id\":12}", "{\"music_id\":11,\"state\":\"PLAYING\"}",
                "{\"music_id\":1.5}", "{\"music_id\":\"11\"}", "{\"music_id\":-1}")) {
            mvc.perform(post(ROOT + "/queue-items").principal(auth).param("membership_id", "3")
                    .param("view_session_id", VIEW).header("Idempotency-Key", COMMAND)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(ROOT + "/queue-items").principal(auth).param("membership_id", "3")
                .param("view_session_id", VIEW).contentType(MediaType.APPLICATION_JSON).content("{\"music_id\":11}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMismatchedAuthenticationAndMapsBusinessErrors() throws Exception {
        auth.setDetails(new TokenClaims(2L, List.of(), Instant.now().plusSeconds(60), SID, "test"));
        mvc.perform(get(ROOT + "/queue-items").principal(auth).param("membership_id", "3")
                .param("view_session_id", VIEW)).andExpect(status().isUnauthorized());
        auth.setDetails(new TokenClaims(1L, List.of(), Instant.now().plusSeconds(60), SID, "test"));
        when(service.add(caller, COMMAND, 11)).thenThrow(new ChatMusicException(429, "COMMAND_RATE_LIMITED"));
        mvc.perform(post(ROOT + "/queue-items").principal(auth).param("membership_id", "3")
                .param("view_session_id", VIEW).header("Idempotency-Key", COMMAND)
                .contentType(MediaType.APPLICATION_JSON).content("{\"music_id\":11}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.data.code").value("COMMAND_RATE_LIMITED"));
    }
}
