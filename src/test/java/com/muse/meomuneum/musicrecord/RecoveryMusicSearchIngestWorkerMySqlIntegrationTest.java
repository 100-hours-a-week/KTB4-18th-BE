package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.ClaimedJob;

class RecoveryMusicSearchIngestWorkerMySqlIntegrationTest extends MusicSearchIngestMySqlSupport {
    @Test
    void loweringMaximumAcrossRestartTerminalizesDurableQueuedRetryWithoutAnotherAttempt() {
        ClaimedJob original;
        try (var context = context(3)) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(item("1", "durable retry")));
            original = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            jobs.fail(original, true, "transient_failure", 300000);
            assertThat(status("1")).isEqualTo("QUEUED");
        }
        try (var context = context(1)) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            assertThat(jobs.claim(1, 120)).isEmpty();
            var recovered = jobs.recover(1, 120).getFirst();
            assertThat(recovered.attemptCount()).isEqualTo(1);
            assertThat(jobs.fail(original, false, "stale_failure", 0)).isFalse();
            assertThat(jobs.fail(recovered, true, "transient_failure", 0)).isTrue();
            assertTerminal(1);
            assertThat(jobs.recover(1, 120)).isEmpty();
        }
    }

    @Test
    void malformedLegacyQueuedAtLimitAndCrashedRecoveryRemainFencedAndDoNotStartAnotherSave() {
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(item("1", "legacy exhausted"), item("2", "another exhausted")));
            jdbc.update("UPDATE music_search_ingest_jobs SET attempt_count=3");
            assertThat(jobs.claim(2, 120)).isEmpty();
            var first = jobs.recover(1, 120);
            assertThat(first).hasSize(1);
            var abandoned = first.getFirst();
            expire(abandoned.id());
            var recovered = jobs.recover(1, 120).getFirst();
            assertThat(recovered.id()).isEqualTo(abandoned.id());
            assertThat(recovered.leaseToken()).isNotEqualTo(abandoned.leaseToken());
            assertThat(jobs.start(recovered, 120)).isNull();
            assertThat(jobs.fail(abandoned, true, "transient_failure", 0)).isFalse();
            assertThat(jobs.fail(recovered, true, "transient_failure", 0)).isTrue();
            assertTerminal(3);
            assertThat(status("2")).isEqualTo("QUEUED");
            assertThat(jobs.recover(1, 120)).hasSize(1);
        }
    }

    private void assertTerminal(int attempts) {
        assertThat(status("1")).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_search_ingest_dlq", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM music_search_ingest_dlq", Integer.class))
                .isEqualTo(attempts);
        assertThat(jdbc.queryForObject("SELECT error_code FROM music_search_ingest_dlq", String.class))
                .isEqualTo("attempts_exhausted");
    }
}
