package com.muse.meomuneum.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

class RecommendationRepositoryTests {
    @Test
    void rejectsMissingGeneratedSessionId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RecommendationRepository repository = new RecommendationRepository(jdbc);
        RecommendationRequest request = new RecommendationRequest("TEXT", "CHATBOT",
                "550e8400-e29b-41d4-a716-446655440000", "비 오는 밤");

        RecommendationException exception = assertThrows(RecommendationException.class,
                () -> repository.createSession(request, "guest", null, Instant.now()));

        assertEquals(500, exception.getStatus());
    }

    @Test
    void rejectsNullMusicId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RecommendationRepository repository = new RecommendationRepository(jdbc);
        TrackData track = new TrackData("ITUNES", "sample", "sample", "artist", null, null);

        RecommendationException exception = assertThrows(RecommendationException.class,
                () -> repository.saveMusic(track));

        assertEquals(500, exception.getStatus());
    }
}
