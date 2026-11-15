package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicSearchIngestDlqRepository;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestAsyncService;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService;
import com.muse.meomuneum.musicrecord.service.MusicSearchRetryPolicy;

class MusicSearchIngestWorkerMySqlIntegrationTest extends MusicSearchIngestMySqlSupport {
    @Test
    void richPayloadSurvivesRestartDeadNewAdmissionReplayAndCatalogReads() {
        var names = new com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.StorefrontNames(
                "US title", "US artist", "한국 제목", "한국 가수", true);
        var original = new MusicItem(null, "ITUNES", "900", "한국 제목", "한국 가수", "cover", "preview", null,
                false, names, 100L);
        long id;
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(original));
            id = jobId("900");
        }
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            var started = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            assertThat(started.music().storefrontNames().titleUs()).isEqualTo("US title");
            assertThat(started.music().releaseEpochSecond()).isEqualTo(100L);
            jobs.fail(started, false, "invalid", 0);
            var historical = jdbc.queryForMap("SELECT title_us,artist_name_us,title_ko,artist_name_ko, "
                    + "release_epoch_second FROM music_search_ingest_dlq");
            var latestNames = new com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.StorefrontNames(
                    "new US", "new artist", "최신 제목", "최신 가수", false);
            var latest = new MusicItem(null, "ITUNES", "900", "최신 제목", "최신 가수", "new cover", "new preview",
                    "abcdefghijk", true, latestNames, 200L);
            jobs.admit(List.of(latest));
            assertThat(status("900")).isEqualTo("DEAD");
            jobs.requeue(id);
            var current = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            jobs.complete(current);
            assertThat(jdbc.queryForMap("SELECT title_us,artist_name_us,title_ko,artist_name_ko, "
                    + "release_epoch_second FROM music_search_ingest_dlq")).isEqualTo(historical);
            var repository = context.getBean(MusicRecordRepository.class);
            var stored = repository.findMusic("ITUNES", "900").orElseThrow();
            assertThat(stored.title()).isEqualTo("최신 제목");
            assertThat(stored.preview_url()).isEqualTo("new preview");
            assertThat(stored.releaseEpochSecond()).isEqualTo(200L);
            assertThat(stored.is_queueable()).isFalse();
            assertThat(repository.searchMusic("최신", Long.MAX_VALUE, Long.MAX_VALUE, 20)).hasSize(1);
            jobs.admit(List.of(new MusicItem(null, "ITUNES", "900", "최신 제목", "최신 가수", null, null, null, false)));
            assertThat(status("900")).isEqualTo("DONE");
            assertThat(jobs.claim(1, 120)).isEmpty();
        }
    }

    @Test
    void twoConcurrentClaimersAreDisjointAndFutureJobsAreExcluded() throws Exception {
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(item("1", "first"), item("2", "second"), item("3", "future")));
            jdbc.update("UPDATE music_search_ingest_jobs SET next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6), "
                    + "INTERVAL 1 DAY) WHERE external_music_id='3'");
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> {
                    start.await();
                    return jobs.claim(1, 120);
                });
                var second = executor.submit(() -> {
                    start.await();
                    return jobs.claim(1, 120);
                });
                start.countDown();
                var a = first.get(5, TimeUnit.SECONDS).getFirst();
                var b = second.get(5, TimeUnit.SECONDS).getFirst();
                assertThat(a.id()).isNotEqualTo(b.id());
                assertThat(a.leaseToken()).isNotEqualTo(b.leaseToken());
                assertThat(a.attemptCount()).isZero();
                assertThat(b.attemptCount()).isZero();
                assertThat(jobs.claim(2, 120)).isEmpty();
            }
        }
    }

    @Test
    void claimReleaseAndUnstartedExpiryNeverConsumeAttemptAndStaleWorkerCannotWrite() {
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(item("1", "song")));
            var stale = jobs.claim(1, 120).getFirst();
            assertThat(jobs.release(stale, 0)).isTrue();
            var expired = jobs.claim(1, 120).getFirst();
            expire(expired.id());
            assertThat(jobs.start(expired, 120)).isNull();
            assertThat(jobs.recover(1, 120)).isEmpty();
            var current = jobs.claim(1, 120).getFirst();
            assertThat(current.attemptCount()).isZero();
            assertThat(jobs.release(stale, 0)).isFalse();
            assertThat(jobs.fail(stale, false, "stale", 0)).isFalse();
            jobs.complete(stale);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isZero();
            var started = jobs.start(current, 120);
            assertThat(started.attemptCount()).isEqualTo(1);
            var forged = new MusicSearchIngestJobService.ClaimedJob(started.id(), item("2", "forged"),
                    started.attemptCount(), started.leaseToken(), started.payloadVersion(), started.observedAt());
            assertThatThrownBy(() -> jobs.complete(forged)).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isZero();
            jobs.complete(started);
            assertThat(status("1")).isEqualTo("DONE");
        }
    }

    @Test
    void startedExpiredAttemptsRecoverToRetryThenAtomicDeadAndSelectedReplay() throws Exception {
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(item("1", "song")));
            var first = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            expire(first.id());
            var recovered = jobs.recover(1, 120).getFirst();
            assertThat(recovered.attemptCount()).isEqualTo(1);
            assertThat(jobs.fail(recovered, true, "expired", 5000)).isTrue();
            assertThat(jobs.claim(1, 120)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,updated_at,next_attempt_at) "
                    + "FROM music_search_ingest_jobs WHERE id=?", Long.class, first.id())).isEqualTo(5000000);
            jdbc.update("UPDATE music_search_ingest_jobs SET attempt_count=2, next_attempt_at=CURRENT_TIMESTAMP(6)");
            var last = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            assertThat(last.attemptCount()).isEqualTo(3);
            expire(last.id());
            var terminal = jobs.recover(1, 120).getFirst();
            jobs.fail(terminal, true, "expired", 5000);
            assertThat(jobs.fail(terminal, true, "expired", 5000)).isFalse();
            assertThat(status("1")).isEqualTo("DEAD");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_search_ingest_dlq", Integer.class)).isEqualTo(1);
            assertThat(jobs.claim(1, 120)).isEmpty();
            var before = jdbc.queryForMap("SELECT payload_version,metadata_observed_at,title "
                    + "FROM music_search_ingest_jobs WHERE id=?", first.id());
            try (var executor = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var a = executor.submit(() -> {
                    start.await();
                    return replay(jobs, first.id());
                });
                var b = executor.submit(() -> {
                    start.await();
                    return replay(jobs, first.id());
                });
                start.countDown();
                assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder("QUEUED", "music_search_job_not_dead");
            }
            assertThat(jdbc.queryForMap("SELECT payload_version,metadata_observed_at,title "
                    + "FROM music_search_ingest_jobs WHERE id=?", first.id())).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT replayed_at IS NOT NULL FROM music_search_ingest_dlq",
                    Boolean.class)).isTrue();
        }
    }

    @Test
    void saveRollbackPrecedesNontransactionalRetryPolicyAndDlqRollbackLeavesRecoverableLease() {
        var music = new MusicRecordRepository(jdbc) {
            @Override
            public void upsertSearchMusic(MusicItem item, LocalDateTime observed, long version) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                super.upsertSearchMusic(item, observed, version);
                throw new DataIntegrityViolationException("forced post-write failure");
            }
        };
        var dlq = new MusicSearchIngestDlqRepository(jdbc) {
            @Override
            public void insert(MusicSearchIngestJobService.ClaimedJob job, String errorCode) {
                super.insert(job, errorCode);
                throw new DataIntegrityViolationException("forced after DLQ insert");
            }
        };
        try (var context = context(music, dlq)) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(item("1", "song")));
            var policy = new MusicSearchRetryPolicy(5000, 300000) {
                @Override
                public long delayMillis(int attempt) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isZero();
                    return super.delayMillis(attempt);
                }
            };
            new MusicSearchIngestAsyncService(jobs, policy).process(jobs.claim(1, 120).getFirst(), 120, () -> {
            });
            assertThat(status("1")).isEqualTo("PROCESSING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_search_ingest_dlq", Integer.class)).isZero();
            expire(jobId("1"));
        }
        try (var recoveredContext = context()) {
            var jobs = recoveredContext.getBean(MusicSearchIngestJobService.class);
            var recovered = jobs.recover(1, 120).getFirst();
            jobs.fail(recovered, false, "permanent_failure", 0);
            assertThat(status("1")).isEqualTo("DEAD");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_search_ingest_dlq", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void saveUsesSeparateNewTransactionAndRowLockPreventsReclaimDuringMusicWrite() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var music = new MusicRecordRepository(jdbc) {
            @Override
            public void upsertSearchMusic(MusicItem item, LocalDateTime observed, long version) {
                entered.countDown();
                try {
                    assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    throw new IllegalStateException(exception);
                }
                super.upsertSearchMusic(item, observed, version);
            }
        };
        try (var context = context(music, new MusicSearchIngestDlqRepository(jdbc));
                var executor = Executors.newSingleThreadExecutor()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(item("1", "song")));
            var started = jobs.start(jobs.claim(1, 120).getFirst(), 1);
            var saved = executor.submit(() -> new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                    .execute(status -> {
                        jobs.complete(started);
                        status.setRollbackOnly();
                        return null;
                    }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                jdbc.queryForObject("SELECT SLEEP(1.1)", Integer.class);
                assertThat(jobs.recover(1, 120)).isEmpty();
                assertThat(jobs.claim(1, 120)).isEmpty();
            } finally {
                finish.countDown();
            }
            saved.get(5, TimeUnit.SECONDS);
            assertThat(status("1")).isEqualTo("DONE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void persistedRetrySurvivesRestartAndConcurrentTerminalTransitionProducesOneEpisode() throws Exception {
        long id;
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(item("1", "song")));
            var started = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            id = started.id();
            jobs.fail(started, true, "transient_failure", 5000);
        }
        try (var context = context(); var executor = Executors.newFixedThreadPool(2)) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            assertThat(jobs.claim(1, 120)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM music_search_ingest_jobs WHERE id=?",
                    Integer.class, id)).isEqualTo(1);
            jdbc.update("UPDATE music_search_ingest_jobs SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
            var started = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            assertThat(started.attemptCount()).isEqualTo(2);
            var ready = new CountDownLatch(1);
            var first = executor.submit(() -> {
                ready.await();
                return jobs.fail(started, false, "permanent", 0);
            });
            var second = executor.submit(() -> {
                ready.await();
                return jobs.fail(started, false, "permanent", 0);
            });
            ready.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_search_ingest_dlq", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void transientSqlSavingFailureRollsBackBeforeDurableJitteredRetry() {
        var music = new MusicRecordRepository(jdbc) {
            @Override
            public void upsertSearchMusic(MusicItem item, LocalDateTime observed, long version) {
                super.upsertSearchMusic(item, observed, version);
                throw new org.springframework.dao.CannotAcquireLockException("forced transient SQL failure",
                        new java.sql.SQLException("deadlock", "40001", 1213));
            }
        };
        try (var context = context(music, new MusicSearchIngestDlqRepository(jdbc))) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            jobs.admit(List.of(item("1", "song")));
            new MusicSearchIngestAsyncService(jobs, new MusicSearchRetryPolicy(5000, 300000))
                    .process(jobs.claim(1, 120).getFirst(), 120, () -> {
                    });
            assertThat(status("1")).isEqualTo("QUEUED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM music_search_ingest_jobs", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND,updated_at,next_attempt_at) "
                    + "FROM music_search_ingest_jobs", Long.class)).isBetween(2500000L, 5000000L);
            assertThat(jobs.claim(1, 120)).isEmpty();
        }
    }

    @Test
    void missingAndNonDeadReplayKeep404And409Contracts() {
        try (var context = context()) {
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            assertThatThrownBy(() -> jobs.requeue(Long.MAX_VALUE)).isInstanceOfSatisfying(
                    com.muse.meomuneum.musicrecord.exception.MusicRecordException.class,
                    exception -> assertThat(exception.status())
                            .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
            jobs.admit(List.of(item("1", "song")));
            assertThatThrownBy(() -> jobs.requeue(jobId("1"))).isInstanceOfSatisfying(
                    com.muse.meomuneum.musicrecord.exception.MusicRecordException.class,
                    exception -> assertThat(exception.status())
                            .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        }
    }

    private String replay(MusicSearchIngestJobService jobs, long id) {
        try {
            return jobs.requeue(id).status();
        } catch (com.muse.meomuneum.musicrecord.exception.MusicRecordException exception) {
            return exception.reason();
        }
    }
}
