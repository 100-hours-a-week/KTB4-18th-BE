package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicSearchIngestJobRepository;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService;
import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.repository.RecommendationRepository;

class FreshnessMusicSearchIngestWorkerMySqlIntegrationTest extends MusicSearchIngestMySqlSupport {
    @Test
    void reversedAndEqualObservationAdmissionsKeepFirstLatestPayloadAndIdenticalWatermark() {
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            var repository = context.getBean(MusicSearchIngestJobRepository.class);
            var now = LocalDateTime.now().minusHours(1);
            admit(repository, "first", now);
            admit(repository, "latest", now.plusSeconds(3));
            admit(repository, "older", now.plusSeconds(2));
            admit(repository, "equal", now.plusSeconds(3));
            assertThat(title()).isEqualTo("latest");
            var started = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            admit(repository, "latest", now.plusSeconds(5));
            admit(repository, "late-stale", now.plusSeconds(4));
            assertThat(status("1")).isEqualTo("PROCESSING");
            jobs.complete(started);
            assertThat(status("1")).isEqualTo("DONE");
            assertThat(context.getBean(MusicRecordRepository.class).findMusic("ITUNES", "1")
                    .orElseThrow().title()).isEqualTo("latest");
            admit(repository, "latest", now.plusSeconds(7));
            admit(repository, "stale-after-done", now.plusSeconds(6));
            assertThat(status("1")).isEqualTo("DONE");
            assertThat(title()).isEqualTo("latest");
            assertThat(jdbc.queryForObject("SELECT payload_version FROM music_search_ingest_jobs", Long.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void changedProcessingPayloadRevokesTokenAndDeadReadmissionPreservesFailureSnapshot() {
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(item("1", "original")));
            var stale = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            jobs.admit(List.of(item("1", "newer")));
            jobs.complete(stale);
            assertThat(jobs.fail(stale, false, "obsolete", 0)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isZero();
            assertThat(status("1")).isEqualTo("QUEUED");
            var current = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            jobs.fail(current, false, "permanent_failure", 0);
            jobs.admit(List.of(item("1", "latest-dead")));
            assertThat(status("1")).isEqualTo("DEAD");
            assertThat(title()).isEqualTo("latest-dead");
            assertThat(jdbc.queryForObject("SELECT title FROM music_search_ingest_dlq", String.class))
                    .isEqualTo("newer");
            jobs.requeue(current.id());
            var replayed = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            assertThat(replayed.payloadVersion()).isEqualTo(3);
            jobs.complete(replayed);
            assertThat(context.getBean(MusicRecordRepository.class).findMusic("ITUNES", "1")
                    .orElseThrow().title()).isEqualTo("latest-dead");
        }
    }

    @Test
    void conditionalUpsertPreservesIdentityNewerMetadataAndKnownLinksForStaleAndEqualInput() {
        var music = new MusicRecordRepository(jdbc);
        var now = LocalDateTime.now().minusHours(1);
        var rich = new com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem(null, "ITUNES", "1",
                "first", "artist", "cover", "preview", "abcdefghijk", true);
        music.upsertSearchMusic(rich, now, 1);
        long id = music.findMusic("ITUNES", "1").orElseThrow().music_id();
        music.upsertSearchMusic(item("1", "latest"), now.plusSeconds(2), 2);
        music.upsertSearchMusic(item("1", "stale"), now.plusSeconds(1), 100);
        music.upsertSearchMusic(item("1", "equal"), now.plusSeconds(2), 2);
        var saved = music.findMusic("ITUNES", "1").orElseThrow();
        assertThat(saved.music_id()).isEqualTo(id);
        assertThat(saved.title()).isEqualTo("latest");
        assertThat(saved.album_cover_url()).isEqualTo("cover");
        assertThat(saved.preview_url()).isEqualTo("preview");
        assertThat(saved.youtube_video_id()).isEqualTo("abcdefghijk");
        music.upsertSearchMusic(item("1", "tie-new-version"), now.plusSeconds(2), 3);
        assertThat(music.findMusic("ITUNES", "1").orElseThrow().title()).isEqualTo("tie-new-version");
    }

    @Test
    void synchronousMusicRecordAndRecommendationWritesFenceOlderQueuedSearchMetadata() {
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            var music = context.getBean(MusicRecordRepository.class);
            jobs.admit(List.of(item("1", "old search"), item("2", "old search")));
            music.upsertMusic(item("1", "record observation"));
            new RecommendationRepository(jdbc).saveMusic(new TrackData("ITUNES", "2", "recommendation observation",
                    "artist", null, null));
            for (var claimed : jobs.claim(2, 120)) {
                jobs.complete(jobs.start(claimed, 120));
            }
            assertThat(music.findMusic("ITUNES", "1").orElseThrow().title()).isEqualTo("record observation");
            assertThat(music.findMusic("ITUNES", "2").orElseThrow().title()).isEqualTo("recommendation observation");
            jobs.admit(List.of(item("1", "newer search")));
            jobs.complete(jobs.start(jobs.claim(1, 120).getFirst(), 120));
            assertThat(music.findMusic("ITUNES", "1").orElseThrow().title()).isEqualTo("newer search");
        }
    }

    private void admit(MusicSearchIngestJobRepository repository, String title, LocalDateTime observed) {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> repository.admit(item("1", title), observed));
    }

    private String title() {
        return jdbc.queryForObject("SELECT title FROM music_search_ingest_jobs WHERE external_music_id='1'",
                String.class);
    }
}
