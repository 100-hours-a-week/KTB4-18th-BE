package com.muse.meomuneum.recommendation;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class RecommendationRepositoryTests {
    @Test
    void rejectsMissingGeneratedSessionId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RecommendationRepository repository = new RecommendationRepository(jdbc);
        RecommendationRequest request = new RecommendationRequest(
                "TEXT", "CHATBOT", "550e8400-e29b-41d4-a716-446655440000", "비 오는 밤");

        // Mock은 생성 키를 채우지 않으므로 저장 ID가 누락된 상황을 재현합니다.
        RecommendationException exception = assertThrows(RecommendationException.class,
                () -> repository.createSession(request, "guest", null, Instant.now()));

        assertEquals(500, exception.getStatus());
    }

    @Test
    void rejectsNullMusicId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RecommendationRepository repository = new RecommendationRepository(jdbc);
        TrackData track = new TrackData("ITUNES", "sample", "sample", "artist", null, null);

        // Mock의 ID 조회 반환값은 NULL입니다.
        RecommendationException exception = assertThrows(RecommendationException.class,
                () -> repository.saveMusic(track));

        assertEquals(500, exception.getStatus());
    }
}
