package com.muse.meomuneum.music;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.muse.meomuneum.music.domain.MusicMetadataPolicy;
import com.muse.meomuneum.music.exception.MusicMetadataException;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

class MusicMetadataPolicyTest {
    @Test
    void acceptsLongArtistNamesAndCountsSupplementaryCharactersOnce() {
        MusicMetadataPolicy.validate("곡", "가".repeat(1000));
        MusicMetadataPolicy.validate("곡", "🎵".repeat(1000));
    }

    @Test
    void rejectsInvalidMetadataBeforeEitherRepositoryWrites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MusicRecordRepository records = new MusicRecordRepository(jdbc);
        RecommendationRepository recommendations = new RecommendationRepository(jdbc);
        for (String artist : Arrays.asList(null, "", "  ", "가".repeat(1001), "🎵".repeat(1001))) {
            assertThatThrownBy(() -> records.upsertMusic(
                    new MusicItem(null, "ITUNES", "123", "곡", artist, null, null, null, false)))
                    .isInstanceOf(MusicMetadataException.class);
            assertThatThrownBy(() -> recommendations.saveMusic(
                    new TrackData("ITUNES", "123", "곡", artist, null, null)))
                    .isInstanceOf(MusicMetadataException.class);
        }
        for (String title : Arrays.asList(null, "", "  ", "가".repeat(256))) {
            assertThatThrownBy(() -> MusicMetadataPolicy.validate(title, "가수"))
                    .isInstanceOf(MusicMetadataException.class);
        }
        verifyNoInteractions(jdbc);
    }
}
