package com.muse.meomuneum.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.recommendation.dto.response.RecommendationHistoryResponse;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;
import com.muse.meomuneum.recommendation.service.RecommendationService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RecommendationHistoryServiceTest {

    @Test
    void returnsCompletedSessionsGroupedByDateWithRankedMusicItems() {
        RecommendationRepository repository = mock(RecommendationRepository.class);
        when(repository.findCompletedSessions(eq(7L), any(), eq(Long.MAX_VALUE), eq(3)))
                .thenReturn(List.of(
                        new RecommendationRepository.HistorySession(30L, "COMPLETED",
                                Instant.parse("2026-09-26T03:00:00Z")),
                        new RecommendationRepository.HistorySession(29L, "COMPLETED",
                                Instant.parse("2026-09-26T01:00:00Z")),
                        new RecommendationRepository.HistorySession(28L, "COMPLETED",
                                Instant.parse("2026-09-25T23:00:00Z"))));
        when(repository.findHistoryItems(List.of(30L, 29L))).thenReturn(List.of(
                new RecommendationRepository.HistoryItem(30L, 1, 101L, "First", "Artist A"),
                new RecommendationRepository.HistoryItem(30L, 2, 102L, "Second", "Artist B"),
                new RecommendationRepository.HistoryItem(29L, 1, 103L, "Third", "Artist C")));
        RecommendationService service = new RecommendationService(mock(), repository, new SimpleMeterRegistry());

        RecommendationHistoryResponse response = service.listHistory(7L, null, 2);

        assertThat(response.has_next()).isTrue();
        assertThat(response.next_cursor()).isNotBlank();
        assertThat(response.groups()).containsExactly(new RecommendationHistoryResponse.Group("2026-09-26", List.of(
                new RecommendationHistoryResponse.Recommendation(30L, "COMPLETED", List.of(
                        new RecommendationHistoryResponse.Item(1, 101L, "First", "Artist A"),
                        new RecommendationHistoryResponse.Item(2, 102L, "Second", "Artist B"))),
                new RecommendationHistoryResponse.Recommendation(29L, "COMPLETED", List.of(
                        new RecommendationHistoryResponse.Item(1, 103L, "Third", "Artist C"))))));
    }
}
