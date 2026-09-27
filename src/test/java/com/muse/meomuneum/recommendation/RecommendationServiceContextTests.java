package com.muse.meomuneum.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @BeforeEach
    void setUp() {
        service = new RecommendationService(provider, repository, new SimpleMeterRegistry());
        when(provider.recommend(any())).thenReturn(tracks());
        when(repository.saveCompleted(any(), any(), any(), anyList()))
                .thenReturn(new RecommendationResponse(1, "COMPLETED", CONVERSATION_KEY, List.of(), Instant.now()));
    }

    @Test
    void limitsHistoryRowsAndProviderContextLength() {
        when(repository.findRecentPrompts(CONVERSATION_KEY, "guest", null, 10))
                .thenReturn(List.of("history-11", "history-10", "history-9", "history-8", "history-7", "history-6",
                        "history-5", "history-4", "history-3", "history-2"));

        service.create(request("current"), "guest", null);

        var command = ArgumentCaptor.forClass(RecommendationCommand.class);
        verify(provider).recommend(command.capture());
        assertEquals(String.join("\n", List.of("history-2", "history-3", "history-4", "history-5", "history-6",
                "history-7", "history-8", "history-9", "history-10", "history-11", "current")),
                command.getValue().message());
        assertTrue(command.getValue().message().length() <= 200);
        assertEquals(java.util.UUID.fromString(CONVERSATION_KEY), command.getValue().threadId());
        verify(repository).findRecentPrompts(CONVERSATION_KEY, "guest", null, 10);
    }

    @Test
    void skipsHistoryQueryWhenCurrentPromptFillsContext() {
        service.create(request("a".repeat(300)), "guest", null);

        var command = ArgumentCaptor.forClass(RecommendationCommand.class);
        verify(provider).recommend(command.capture());
        assertEquals("a".repeat(200), command.getValue().message());
        verify(repository, never()).findRecentPrompts(any(), any(), any(), anyInt());
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
