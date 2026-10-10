package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;
import com.muse.meomuneum.musicrecord.service.MusicSearchStorageService;

@Testcontainers
class MusicSearchStorageMySqlIntegrationTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("music_storage_test").withUsername("test").withPassword("test");
    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private MusicRecordRepository repository;

    @BeforeAll
    static void schema() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__recommendation_sample.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/migration/V20261002133615__expand_music_artist_name_length.sql"));
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM music");
        repository = new MusicRecordRepository(jdbc);
    }

    @Test
    void insertsEveryFieldAndPreservesEveryExistingFieldIncludingIdAndTimestamps() {
        MusicItem original = new MusicItem(null, "ITUNES", "1", "original", "🎵".repeat(1000),
                "https://example.test/cover", "https://example.test/preview", "abcdefghijk", true);
        repository.insertMusicIfAbsent(original);
        jdbc.update("UPDATE music SET created_at='2026-01-01 01:02:03.123456', "
                + "updated_at='2026-02-01 04:05:06.654321' WHERE external_music_id='1'");
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM music WHERE external_music_id='1'");
        var stored = repository.findMusic("ITUNES", "1").orElseThrow();
        assertThat(stored.title()).isEqualTo(original.title());
        assertThat(stored.artist_name()).isEqualTo(original.artist_name());
        assertThat(stored.album_cover_url()).isEqualTo(original.album_cover_url());
        assertThat(stored.preview_url()).isEqualTo(original.preview_url());
        assertThat(stored.youtube_video_id()).isEqualTo(original.youtube_video_id());
        assertThat(stored.is_queueable()).isTrue();
        var storage = storage(repository, new DataSourceTransactionManager(dataSource));
        storage.store(List.of(item("1", "replacement"), item("2", "new")));
        storage.store(List.of(item("1", "another replacement")));
        assertThat(jdbc.queryForMap("SELECT * FROM music WHERE external_music_id='1'")).isEqualTo(before);
        assertThat(repository.findMusic("ITUNES", "2").orElseThrow().title()).isEqualTo("new");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentDuplicateInsertionProducesExactlyOneRowAndPreservesExistingRow() throws Exception {
        repository.insertMusicIfAbsent(item("1", "original"));
        jdbc.update("UPDATE music SET updated_at='2026-02-01 04:05:06.654321' WHERE external_music_id='1'");
        var before = jdbc.queryForMap("SELECT * FROM music WHERE external_music_id='1'");
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                int candidate = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    try {
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    storage(repository, new DataSourceTransactionManager(dataSource))
                            .store(List.of(item("1", "changed-" + candidate), item("2", "new-" + candidate)));
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music WHERE external_music_id='2'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM music WHERE external_music_id='1'")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isEqualTo(2);
    }

    @Test
    void failedAttemptRollsBackItsInsertBeforeNewTransactionRetryAndNextTrack() {
        var events = new ArrayList<String>();
        var attempts = new AtomicInteger();
        var manager = new DataSourceTransactionManager(dataSource) {
            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                assertThat(definition.getPropagationBehavior())
                        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                events.add("begin");
                super.doBegin(transaction, definition);
            }
            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                events.add("rollback");
                super.doRollback(status);
            }
            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                events.add("commit");
                super.doCommit(status);
            }
        };
        var failing = new MusicRecordRepository(jdbc) {
            @Override
            public void insertMusicIfAbsent(MusicItem item) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                if (item.external_music_id().equals("1")) {
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music WHERE external_music_id='1'",
                            Integer.class)).isZero();
                    super.insertMusicIfAbsent(item);
                    if (attempts.incrementAndGet() == 1) {
                        throw new TransientDataAccessResourceException("forced after insert");
                    }
                } else {
                    super.insertMusicIfAbsent(item);
                }
            }
        };
        storage(failing, manager).store(List.of(item("1", "song"), item("2", "next")));
        assertThat(attempts).hasValue(2);
        assertThat(events).containsExactly("begin", "rollback", "begin", "commit", "begin", "commit");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isEqualTo(2);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void actualDatabaseConstraintFailureDoesNotRetryOrBlockNextTrack() {
        var attempts = new AtomicInteger();
        var failing = new MusicRecordRepository(jdbc) {
            @Override
            public void insertMusicIfAbsent(MusicItem item) {
                attempts.incrementAndGet();
                super.insertMusicIfAbsent(item);
            }
        };
        var invalid = new MusicItem(null, "ITUNES", "1", "song", "artist", "x".repeat(1001), null, null, false);
        storage(failing, new DataSourceTransactionManager(dataSource)).store(List.of(invalid, item("2", "next")));
        assertThat(attempts).hasValue(2);
        assertThat(repository.findMusic("ITUNES", "1")).isEmpty();
        assertThat(repository.findMusic("ITUNES", "2")).isPresent();
    }

    @Test
    void completedCollectionRetainsCursorHashAndPagesAcrossRawResultsBeyondTwenty() throws Exception {
        repository.insertMusicIfAbsent(item("1", "unrelated"));
        jdbc.update("UPDATE music SET preview_url='original-preview', youtube_video_id='abcdefghijk', "
                + "updated_at='2026-01-01 00:00:00.123456' WHERE external_music_id='1'");
        var before = jdbc.queryForMap("SELECT * FROM music WHERE external_music_id='1'");
        var raw = new ArrayList<MusicItem>();
        for (int i = 1; i <= 25; i++) {
            raw.add(item(String.valueOf(i), "song-" + i));
        }
        var itunes = mock(ItunesMusicSearchClient.class);
        when(itunes.search("song")).thenReturn(raw);
        var release = new CountDownLatch(1);
        var collected = new CountDownLatch(1);
        var snapshots = new ArrayList<List<MusicItem>>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var worker = Executors.newSingleThreadExecutor();
        var real = storage(repository, new DataSourceTransactionManager(dataSource));
        var async = new MusicSearchStorageService(repository, new RetryTemplate(),
                new DataSourceTransactionManager(dataSource)) {
            @Override
            public void store(List<MusicItem> snapshot) {
                snapshots.add(snapshot);
                if (snapshots.size() == 1) {
                    worker.submit(() -> {
                        try {
                            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                            real.store(snapshot);
                        } catch (Throwable exception) {
                            failure.set(exception);
                        } finally {
                            collected.countDown();
                        }
                    });
                }
            }
        };
        try {
            var service = new MusicRecordService(repository, itunes, mock(LocationResolutionTokenProvider.class),
                    new MusicSearchCursorCodec("test-secret"), async);
            var first = service.search("song", "ITUNES", null, 20);
            assertThat(first.items()).hasSize(20);
            assertThat(first.has_next()).isTrue();
            assertThat(snapshots.getFirst()).hasSize(25);
            assertThat(snapshots.getFirst().getFirst().preview_url()).isNull();
            assertThat(first.items().getFirst().preview_url()).isEqualTo("original-preview");
            release.countDown();
            assertThat(collected.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isEqualTo(25);
            var next = service.search("song", "ITUNES", first.next_cursor(), 20);
            assertThat(next.items()).hasSize(5);
            assertThat(next.has_next()).isFalse();
            var all = new ArrayList<>(first.items());
            all.addAll(next.items());
            assertThat(all).extracting(MusicItem::external_music_id)
                    .containsExactlyElementsOf(raw.stream().map(MusicItem::external_music_id).toList());
            assertThat(all.stream().map(MusicItem::external_music_id).distinct()).hasSize(25);
            assertThat(jdbc.queryForMap("SELECT * FROM music WHERE external_music_id='1'")).isEqualTo(before);
            assertThat(repository.searchMusic("song", Long.MAX_VALUE,
                    first.items().getFirst().music_id(), 30)).isEmpty();
        } finally {
            release.countDown();
            worker.shutdown();
            assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private MusicSearchStorageService storage(MusicRecordRepository records, DataSourceTransactionManager manager) {
        var retry = new RetryTemplate(RetryPolicy.builder().maxRetries(3).delay(Duration.ZERO)
                .predicate(MusicSearchStorageService::isRetryable).build());
        return new MusicSearchStorageService(records, retry, manager);
    }

    private MusicItem item(String id, String title) {
        return new MusicItem(null, "ITUNES", id, title, "artist", null, null, null, false);
    }
}
