package com.muse.meomuneum.music;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.provider.RecommendationProvider;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;
import com.muse.meomuneum.recommendation.service.RecommendationEventStream;
import com.muse.meomuneum.recommendation.service.RecommendationService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MusicMetadataRecommendationTest {
    @Test
    void invalidArtistFailsSessionWithoutPublishingTracksOrDone() {
        var provider = mock(RecommendationProvider.class);
        var repository = mock(RecommendationRepository.class);
        var events = mock(RecommendationEventStream.class);
        var service = new RecommendationService(provider, repository, new SimpleMeterRegistry(), events, Runnable::run);
        when(repository.createSession(any(), any(), any(), any())).thenReturn(1L);
        when(provider.recommend(any())).thenReturn(
                List.of(new TrackData("ITUNES", "123", "曲", "가".repeat(1001), null, null)));
        service.accept(new RecommendationRequest("TEXT", "CHATBOT", "550e8400-e29b-41d4-a716-446655440000", "산책"),
                "guest", null);
        verify(repository).fail(eq(1L), eq("FAILED"), any());
        verify(events).error(1L, "추천된 곡의 음악 정보를 저장할 수 없어요. 다른 곡을 추천받아 주세요.");
        verify(repository, never()).completeSession(anyLong(), any(), anyList());
        verify(events, never()).tracks(anyLong(), any());
        verify(events, never()).done(anyLong(), any());
    }
}
