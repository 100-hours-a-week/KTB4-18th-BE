package com.muse.meomuneum.chat.music;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.muse.meomuneum.chat.participation.ChatParticipationService;
import com.muse.meomuneum.global.security.TokenClaims;

class ChatMusicPlaybackControllerTest {
    private static final String SID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VIEW = "550e8400-e29b-41d4-a716-446655440001";
    private static final String COMMAND = "550e8400-e29b-41d4-a716-446655440002";
    private static final String PATH = "/api/v1/chat-rooms/7/playback/commands";
    private ChatMusicPlaybackService playback;
    private ChatParticipationService participation;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken auth;
    private ChatMusicQueueService.Caller caller;

    @BeforeEach
    void setup() {
        playback = mock(ChatMusicPlaybackService.class);
        participation = mock(ChatParticipationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChatMusicPlaybackController(playback, participation))
                .setControllerAdvice(new ChatMusicExceptionHandler()).build();
        auth = UsernamePasswordAuthenticationToken.authenticated(1L, null, List.of());
        auth.setDetails(new TokenClaims(1L, List.of("USER"), Instant.now().plusSeconds(60), SID, "test"));
        caller = new ChatMusicQueueService.Caller(1, SID, 7, 3, VIEW);
    }

    @Test
    void acceptsPlayAndAllCurrentItemCommandsWithStableJsonAndNoStore() throws Exception {
        for (String action : List.of("PLAY", "PAUSE", "RESUME", "SKIP")) {
            Long target = action.equals("PLAY") ? null : 12L;
            when(playback.control(participation, caller, COMMAND, action, target, 1))
                    .thenReturn(new ChatMusicQueueService.Result(200,
                            "{\"message\":\"completed\",\"data\":{\"state\":\"PLAYING\",\"version\":2}}"));
            mvc.perform(post(PATH).principal(auth).param("membership_id", "3").param("view_session_id", VIEW)
                    .header("Idempotency-Key", COMMAND).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + action + "\",\"expected_version\":1,\"queue_item_id\":" + target + "}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.version").value(2));
        }
    }

    @Test
    void rejectsDuplicateUnknownMissingNonIntegerAndTrailingJson() throws Exception {
        for (String body : List.of(
                "{\"action\":\"PLAY\",\"action\":\"SKIP\",\"expected_version\":1,\"queue_item_id\":null}",
                "{\"action\":\"ENDED\",\"expected_version\":1,\"queue_item_id\":12}",
                "{\"action\":\"PLAY\",\"expected_version\":1}",
                "{\"action\":\"PLAY\",\"expected_version\":1.5,\"queue_item_id\":null}",
                "{\"action\":\"PLAY\",\"expected_version\":1,\"queue_item_id\":null,\"position_seconds\":20}",
                "{\"action\":\"PLAY\",\"expected_version\":1,\"queue_item_id\":null} {}")) {
            mvc.perform(post(PATH).principal(auth).param("membership_id", "3").param("view_session_id", VIEW)
                    .header("Idempotency-Key", COMMAND).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.data.code").value("INVALID_PLAYBACK_COMMAND"));
        }
        verifyNoInteractions(playback);
    }

    @Test
    void missingIdempotencyAndMismatchedJwtAreRejectedAndConflictUsesCommonAdvice() throws Exception {
        mvc.perform(post(PATH).principal(auth).param("membership_id", "3").param("view_session_id", VIEW)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.code").value("INVALID_REQUEST"));
        auth.setDetails(new TokenClaims(2L, List.of("USER"), Instant.now().plusSeconds(60), SID, "test"));
        mvc.perform(post(PATH).principal(auth).param("membership_id", "3").param("view_session_id", VIEW)
                .header("Idempotency-Key", COMMAND).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        auth.setDetails(new TokenClaims(1L, List.of("USER"), Instant.now().plusSeconds(60), SID, "test"));
        when(playback.control(participation, caller, COMMAND, "SKIP", 12L, 1))
                .thenThrow(new ChatMusicException(409, "VERSION_CONFLICT"));
        mvc.perform(post(PATH).principal(auth).param("membership_id", "3").param("view_session_id", VIEW)
                .header("Idempotency-Key", COMMAND).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"SKIP\",\"expected_version\":1,\"queue_item_id\":12}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.code").value("VERSION_CONFLICT"));
    }
}
