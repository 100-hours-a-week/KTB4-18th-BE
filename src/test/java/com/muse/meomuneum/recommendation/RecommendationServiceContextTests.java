package com.muse.meomuneum.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.dto.response.RecommendationResponse;
import com.muse.meomuneum.recommendation.provider.RecommendationCommand;
import com.muse.meomuneum.recommendation.provider.RecommendationProvider;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;
import com.muse.meomuneum.recommendation.service.RecommendationEventStream;
import com.muse.meomuneum.recommendation.service.RecommendationService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceContextTests {
    private static final String CONVERSATION_KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    RecommendationProvider provider;
    @Mock
    RecommendationRepository repository;
    RecommendationService service;
    RecommendationEventStream eventStream;

    @BeforeEach
    void setUp() {
        eventStream = new RecommendationEventStream();
        service = new RecommendationService(provider, repository, new SimpleMeterRegistry(), eventStream,
                Runnable::run);
        when(repository.createSession(any(), any(), any(), any())).thenReturn(1L);
        when(provider.recommend(any())).thenReturn(tracks());
        when(repository.completeSession(any(Long.class), any(), anyList()))
                .thenReturn(new RecommendationResponse(1, "COMPLETED", CONVERSATION_KEY, List.of(), Instant.now()));
    }

    @Test
    void sendsOnlyCurrentPromptToProvider() {
        service.accept(request("current"), "guest", null);

        var command = ArgumentCaptor.forClass(RecommendationCommand.class);
        verify(provider).recommend(command.capture());
        assertEquals("current", command.getValue().message());
        assertTrue(command.getValue().message().length() <= 200);
        assertEquals(java.util.UUID.fromString(CONVERSATION_KEY), command.getValue().threadId());
        verify(repository).completeSession(any(Long.class), any(), anyList());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void passesEntireBoundaryPromptToAi() {
        String prompt = "앞부분" + "가".repeat(192) + "🎵" + "끝부분";
        service.accept(request(prompt), "guest", null);

        var command = ArgumentCaptor.forClass(RecommendationCommand.class);
        verify(provider).recommend(command.capture());
        assertEquals(prompt, command.getValue().message());
    }

    private RecommendationRequest request(String prompt) {
        return new RecommendationRequest("TEXT", "CHATBOT", CONVERSATION_KEY, prompt);
    }

    private List<TrackData> tracks() {
        return List.of(new TrackData("ITUNES", "1", "song 1", "artist", null, null),
                new TrackData("ITUNES", "2", "song 2", "artist", null, null),
                new TrackData("ITUNES", "3", "song 3", "artist", null, null),
                new TrackData("ITUNES", "4", "song 4", "artist", null, null),
                new TrackData("ITUNES", "5", "song 5", "artist", null, null));
    }
}
