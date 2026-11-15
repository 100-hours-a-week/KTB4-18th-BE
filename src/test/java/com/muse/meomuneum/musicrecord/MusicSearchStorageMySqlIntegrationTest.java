package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.music.exception.MusicMetadataException;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicSearchIngestJobRepository;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.ClaimedJob;
import com.muse.meomuneum.musicrecord.service.MusicSearchStorageService;

@Testcontainers
class MusicSearchStorageMySqlIntegrationTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("music_storage_test").withUsername("test").withPassword("test");
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void schema() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("db/migration/V1__recommendation_sample.sql"));
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource(
                    "db/migration/V20261002133615__expand_music_artist_name_length.sql"));
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource(
                    "db/music-search-legacy/V20261110093705__create_music_search_ingest_jobs.sql"));
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource(
                    "db/migration/V20261115190842__add_music_search_storage_recovery.sql"));
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource(
                    "db/migration/V20261115204849__align_music_search_ingest_metadata_columns.sql"));
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource(
                    "db/migration/V20261115233805__align_music_catalog_compatibility.sql"));
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource(
                    "db/migration/V20261115233818__extend_music_search_dlq_payload.sql"));
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM music_search_ingest_dlq");
        jdbc.update("DELETE FROM music_search_ingest_jobs");
        jdbc.update("DELETE FROM music");
    }

    @Test
    void admissionCommitAcknowledgmentFailureReturns503WithWholeBatchAndRetryIsIdempotent() {
        var manager = new AcknowledgmentFailureTransactionManager(dataSource);
        try (var context = context(manager, new MusicSearchIngestJobRepository(jdbc))) {
            var storage = context.getBean(MusicSearchStorageService.class);
            List<MusicItem> snapshot = List.of(item("1", "original-1"), item("2", "original-2"));

            assertThatThrownBy(() -> storage.store(snapshot))
                    .isInstanceOfSatisfying(MusicRecordException.class,
                            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(jobCount()).isEqualTo(2);

            storage.store(List.of(item("1", "replacement-1"), item("2", "replacement-2")));

            assertThat(jobCount()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT title FROM music_search_ingest_jobs WHERE external_music_id='1'",
                    String.class)).isEqualTo("replacement-1");
        }
    }

    @Test
    void admissionFailureBeforeCommitRollsBackEveryInsertedRow() {
        var repository = new FailingAdmissionRepository(jdbc);
        try (var context = context(new DataSourceTransactionManager(dataSource), repository)) {
            assertThatThrownBy(() -> context.getBean(MusicSearchStorageService.class)
                    .store(List.of(item("1", "song-1"), item("2", "song-2"))))
                    .isInstanceOfSatisfying(MusicRecordException.class,
                            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(jobCount()).isZero();
        }
    }

    @Test
    void successfulExternalSearchReturnsAfterUniqueJobsAreCommitted() {
        try (var context = context(new DataSourceTransactionManager(dataSource),
                new MusicSearchIngestJobRepository(jdbc))) {
            var repository = context.getBean(MusicRecordRepository.class);
            var itunes = mock(ItunesMusicSearchClient.class);
            var snapshot = new java.util.ArrayList<MusicItem>();
            for (int index = 21; index < 46; index++) {
                snapshot.add(new MusicItem(null, "ITUNES", Integer.toString(index), "query " + index,
                        "artist", "cover", "preview", null, false,
                        new com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.StorefrontNames(
                                "US " + index, "US artist", "한국 " + index, "한국 가수", false),
                        100L + index));
            }
            snapshot.add(snapshot.getFirst());
            when(itunes.search("query")).thenReturn(snapshot);
            when(itunes.country()).thenReturn("KR");
            var service = new MusicRecordService(repository, itunes, mock(LocationResolutionTokenProvider.class),
                    new MusicSearchCursorCodec("test-secret"),
                    new com.muse.meomuneum.musicrecord.service.MusicSearchCandidateCache(java.time.Clock.systemUTC()),
                    context.getBean(MusicSearchStorageService.class));
            var response = service.search("query", "ITUNES", null, 1);
            assertThat(response.items()).hasSize(1);
            assertThat(jobCount()).isEqualTo(25);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_search_ingest_jobs WHERE title_us IS NOT NULL "
                    + "AND artist_name_us IS NOT NULL AND title_ko IS NOT NULL AND artist_name_ko IS NOT NULL "
                    + "AND release_epoch_second IS NOT NULL", Integer.class)).isEqualTo(25);
            assertThat(jobStatus("21")).isEqualTo("QUEUED");
            assertThat(jobStatus("22")).isEqualTo("QUEUED");
        }
    }

    @Test
    void mixedInvalidSnapshotAdmitsNoRowsAndOneThousandCodePointArtistPersistsLosslessly() {
        try (var context = context(new DataSourceTransactionManager(dataSource),
                new MusicSearchIngestJobRepository(jdbc))) {
            var storage = context.getBean(MusicSearchStorageService.class);
            assertThatThrownBy(() -> storage.store(List.of(item("1", "valid"), item("2", "🎵".repeat(1001)))))
                    .isInstanceOf(MusicMetadataException.class);
            assertThat(jobCount()).isZero();

            String artist = "🎵".repeat(1000);
            storage.store(List.of(new MusicItem(null, "ITUNES", "3", "song", artist,
                    null, null, null, false)));
            assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(artist_name) FROM music_search_ingest_jobs "
                    + "WHERE external_music_id='3'", Integer.class)).isEqualTo(1000);
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            ClaimedJob claim = jobs.claim(1, 120).getFirst();
            jobs.complete(jobs.start(claim, 120));

            assertThat(context.getBean(MusicRecordRepository.class).findMusic("ITUNES", "3")
                    .orElseThrow().artist_name()).isEqualTo(artist);
            assertThat(jobStatus("3")).isEqualTo("DONE");
        }
    }

    @Test
    void concurrentAdmissionsCreateOneJobPerIdentityAndDoNotResetFailedState() throws Exception {
        try (var context = context(new DataSourceTransactionManager(dataSource),
                new MusicSearchIngestJobRepository(jdbc))) {
            var storage = context.getBean(MusicSearchStorageService.class);
            var ready = new CountDownLatch(8);
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(8)) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (int index = 0; index < 8; index++) {
                    int candidate = index;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        try {
                            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        storage.store(List.of(item("1", "song-" + candidate), item("2", "song-" + candidate)));
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (var future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                start.countDown();
            }

            assertThat(jobCount()).isEqualTo(2);
            var jobs = context.getBean(MusicSearchIngestJobService.class);
            var started = jobs.start(jobs.claim(1, 120).getFirst(), 120);
            jobs.fail(started, false, "permanent_failure", 0);
            storage.store(List.of(item(started.music().external_music_id(), "later-search")));
            assertThat(jobStatus(started.music().external_music_id())).isEqualTo("DEAD");
            assertThat(jdbc.queryForObject("SELECT title FROM music_search_ingest_jobs WHERE id=?",
                    String.class, started.id())).isEqualTo("later-search");
            assertThat(jdbc.queryForObject("SELECT title FROM music_search_ingest_dlq WHERE job_id=?",
                    String.class, started.id())).isEqualTo(started.music().title());
        }
    }

    private AnnotationConfigApplicationContext context(PlatformTransactionManager manager,
            MusicSearchIngestJobRepository repository) {
        var context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfiguration.class);
        context.registerBean("transactionManager", PlatformTransactionManager.class, () -> manager);
        context.registerBean("jobRepository", MusicSearchIngestJobRepository.class, () -> repository);
        context.registerBean(MusicRecordRepository.class, () -> new MusicRecordRepository(jdbc));
        context.registerBean(com.muse.meomuneum.musicrecord.repository.MusicSearchIngestDlqRepository.class,
                () -> new com.muse.meomuneum.musicrecord.repository.MusicSearchIngestDlqRepository(jdbc));
        context.registerBean(MusicSearchIngestJobService.class);
        context.registerBean(MusicSearchStorageService.class);
        context.refresh();
        return context;
    }

    private int jobCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM music_search_ingest_jobs", Integer.class);
    }

    private String jobStatus(String externalId) {
        return jdbc.queryForObject("SELECT status FROM music_search_ingest_jobs WHERE external_music_id=?",
                String.class, externalId);
    }

    private MusicItem item(String id, String title) {
        return new MusicItem(null, "ITUNES", id, title, "artist", null, null, null, false);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }

    static class AcknowledgmentFailureTransactionManager extends DataSourceTransactionManager {
        private boolean failFirstCommit = true;

        AcknowledgmentFailureTransactionManager(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status)
                throws TransactionException {
            super.doCommit(status);
            if (failFirstCommit) {
                failFirstCommit = false;
                throw new TransactionSystemException("forced commit acknowledgment failure");
            }
        }
    }

    static class FailingAdmissionRepository extends MusicSearchIngestJobRepository {
        FailingAdmissionRepository(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public void admit(List<MusicItem> items) {
            super.admit(List.of(items.getFirst()));
            throw new DataIntegrityViolationException("forced admission failure");
        }
    }
}
