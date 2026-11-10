package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.global.exception.GlobalExceptionHandler;
import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.musicrecord.controller.MusicRecordController;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateRequest;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSelection;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.StorefrontNames;
import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Request;
import com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.Response;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.provider.ItunesMusicSearchClient;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.resolver.CurrentUserResolver;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCandidateCache;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;
import com.muse.meomuneum.musicrecord.service.SongRegistrationService;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

@Testcontainers
class SongRegistrationMySqlIntegrationTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("song_registration_test").withUsername("test").withPassword("test");
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private MusicRecordRepository repository;
    private ItunesMusicSearchClient itunes;
    private MusicSearchCandidateCache cache;
    private MusicRecordService search;

    @BeforeAll
    static void schema() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        for (String file : List.of("V1__recommendation_sample.sql",
                "V20261002133615__expand_music_artist_name_length.sql",
                "V20261110133622__add_music_storefront_names.sql",
                "V20261110135304__add_music_release_epoch.sql",
                "V20260921200528__create_users_table.sql", "V20260923142421__create_chat_room_foundation.sql",
                "V20260925174748__create_map_dots_and_music_records_tables.sql",
                "V20261108192420__add_chat_music_catalog_foundation.sql")) {
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + file));
            }
        }
        jdbc.update("INSERT INTO users (id,email,password_hash,nickname,role) "
                + "VALUES (1,'fixture@example.test','fixture','fixture','USER')");
        jdbc.update("INSERT INTO regions (id,code,name,level) VALUES (1,'11','서울','SIDO')");
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level) VALUES (2,1,'11440','마포','SIGUNGU')");
        jdbc.update("INSERT INTO map_dots (id,code,region_id,latitude,longitude) VALUES (1,'dot',2,37.5,127)");
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM music_records");
        jdbc.update("DELETE FROM music");
        repository = new MusicRecordRepository(jdbc);
        itunes = mock(ItunesMusicSearchClient.class);
        when(itunes.country()).thenReturn("KR");
        cache = new MusicSearchCandidateCache(Clock.systemUTC());
        search = search(itunes, cache);
    }

    @Test
    void registrationStoresBothNamesAndSearchesBothEvenAfterAMetadataRetry() {
        var names = new StorefrontNames("Landing in Love", "HANRORO", "사랑하게 될 거야", "한로로", false);
        var candidate = new MusicItem(null, "ITUNES", "1703196825", names.titleKo(), names.artistNameKo(),
                null, null, null, false, names);
        cache.put("track", "KR", List.of(candidate));

        var registered = registration(itunes, cache).register(new Request("1703196825"));

        assertThat(registered.created()).isTrue();
        long id = registered.music().music_id();
        assertThat(registered.music().title()).isEqualTo(names.titleKo());
        repository.upsertMusic(new MusicItem(null, "ITUNES", "1703196825", names.titleUs(), names.artistNameUs(),
                null, null, null, false, new StorefrontNames(names.titleUs(), names.artistNameUs(), null, null, true)));
        var row = jdbc.queryForMap("SELECT title_us, artist_name_us, title_ko, artist_name_ko FROM music WHERE id=?",
                id);
        assertThat(row).containsEntry("title_us", names.titleUs()).containsEntry("artist_name_us", names.artistNameUs())
                .containsEntry("title_ko", names.titleKo()).containsEntry("artist_name_ko", names.artistNameKo());
        for (String query : List.of("Landing in Love", "HANRORO", "사랑하게 될 거야", "한로로")) {
            assertThat(repository.searchMusic(query, Long.MAX_VALUE, id, 20))
                    .extracting(MusicItem::title).containsExactly(names.titleKo());
        }
    }

    @Test
    void realMysqlPagesByReleaseAcrossTiesAndMissingDatesWithAPinnedHighWatermark() {
        for (int externalId = 1; externalId <= 22; externalId++) {
            Long release = switch (externalId) {
                case 1 -> 9L;
                case 2 -> 10L;
                case 3, 5 -> 8L;
                case 4 -> 12L;
                default -> null;
            };
            repository.insertSelectedMusic(releaseItem(externalId, release));
        }

        var page = search.search("release test", "ITUNES", null, 2);
        assertThat(page.source()).isEqualTo("DB");
        assertThat(page.items()).extracting(MusicItem::external_music_id).containsExactly("4", "2");
        assertThat(page.items()).extracting(MusicItem::releaseEpochSecond).containsExactly(12L, 10L);
        long highWatermark = repository.highestMusicId();
        repository.upsertMusic(releaseItem(2, 18L));
        assertThat(repository.findMusic("ITUNES", "2").orElseThrow().releaseEpochSecond()).isEqualTo(10L);
        repository.upsertMusic(releaseItem(22, 7L));
        assertThat(repository.findMusic("ITUNES", "22").orElseThrow().releaseEpochSecond()).isNull();
        repository.insertSelectedMusic(releaseItem(23, 1L));

        var remaining = new ArrayList<String>();
        while (page.has_next()) {
            page = search.search("release test", "ITUNES", page.next_cursor(), 2);
            assertThat(page.source()).isEqualTo("DB");
            remaining.addAll(page.items().stream().map(MusicItem::external_music_id).toList());
        }
        var expected = new ArrayList<>(List.of("1", "5", "3"));
        for (int externalId = 22; externalId >= 6; externalId--) {
            expected.add(Integer.toString(externalId));
        }
        assertThat(remaining).containsExactlyElementsOf(expected);

        long fourthMusicId = repository.findMusic("ITUNES", "4").orElseThrow().music_id();
        var codec = new MusicSearchCursorCodec("secret");
        String legacy = codec.encode("release test", "KR", 2, codec.database(fourthMusicId, highWatermark));
        var oldPage = search.search("release test", "ITUNES", legacy, 2);
        assertThat(oldPage.items()).extracting(MusicItem::external_music_id).containsExactly("3", "2");
        assertThat(codec.decode(oldPage.next_cursor(), "release test", "KR", 2).legacyDatabaseOrder()).isTrue();
        assertThat(search.search("release test", "ITUNES", oldPage.next_cursor(), 2).items())
                .extracting(MusicItem::external_music_id).containsExactly("1");
    }

    @Test
    void realMysqlPagesByExactAndPartialNameBeforeReleaseAndPreservesDateOnlyCursors() {
        var names = new StorefrontNames("US title", "US Artist", "한국 곡", "한로로 노래", false);
        repository.insertSelectedMusic(new MusicItem(null, "ITUNES", "1", "한국 곡", "다른 표시 이름",
                null, null, null, false, names, 10L));
        insertRankedSong(2, "다른 곡", "한 로 로   노래", 20L);
        insertRankedSong(3, "한로로 노래 라이브", "가수", 50L);
        insertRankedSong(4, "다른 곡", "밴드 한로로 노래", 50L);
        insertRankedSong(5, "한로로 라이브", "노래 가수", 300L);
        insertRankedSong(6, "한로로 노래", "가수", null);
        for (int externalId = 7; externalId <= 22; externalId++) {
            insertRankedSong(externalId, "한로로 라이브", "노래 가수", null);
        }

        String query = "한로로 노래";
        var page = search.search(query, "ITUNES", null, 2);
        assertThat(page.source()).isEqualTo("DB");
        assertThat(page.items()).extracting(MusicItem::external_music_id).containsExactly("2", "1");
        assertThat(page.items()).extracting(MusicItem::matchPriority).containsExactly(0, 0);
        assertThat(new ObjectMapper().valueToTree(page.items().getFirst()).size()).isEqualTo(9);
        var codec = new MusicSearchCursorCodec("secret");
        assertThat(codec.decode(page.next_cursor(), query, "KR", 2).lastMatchPriority()).isZero();
        long highWatermark = repository.highestMusicId();
        insertRankedSong(23, "한로로 노래", "가수", 400L);

        var remaining = new ArrayList<String>();
        for (int pageNumber = 0; page.has_next() && pageNumber < 12; pageNumber++) {
            page = search.search(query, "ITUNES", page.next_cursor(), 2);
            assertThat(page.source()).isEqualTo("DB");
            remaining.addAll(page.items().stream().map(MusicItem::external_music_id).toList());
        }
        assertThat(page.has_next()).isFalse();
        var expected = new ArrayList<>(List.of("6", "4", "3", "5"));
        for (int externalId = 22; externalId >= 7; externalId--) {
            expected.add(Integer.toString(externalId));
        }
        assertThat(remaining).containsExactlyElementsOf(expected);

        long newestMusicId = repository.findMusic("ITUNES", "5").orElseThrow().music_id();
        String dated = codec.encode(query, "KR", 2, codec.database(newestMusicId, highWatermark, 300L));
        var oldPage = search.search(query, "ITUNES", dated, 2);
        assertThat(oldPage.items()).extracting(MusicItem::external_music_id).containsExactly("4", "3");
        assertThat(codec.decode(oldPage.next_cursor(), query, "KR", 2).lastMatchPriority()).isNull();
        assertThat(search.search(query, "ITUNES", oldPage.next_cursor(), 2).items())
                .extracting(MusicItem::external_music_id).containsExactly("2", "1");
        verify(itunes, never()).search(query);
    }

    private void insertRankedSong(int externalId, String title, String artist, Long release) {
        repository.insertSelectedMusic(new MusicItem(null, "ITUNES", Integer.toString(externalId), title, artist,
                null, null, null, false, StorefrontNames.EMPTY, release));
    }

    @Test
    void realMysqlThresholdAndSizeIndependentProbePinEveryDatabasePage() {
        for (int count : List.of(0, 1, 19, 20, 21)) {
            jdbc.update("DELETE FROM music");
            for (int id = 1; id <= count; id++) {
                repository.insertSelectedMusic(item("" + id, "song", "artist"));
            }
            when(itunes.search("song")).thenReturn(List.of(item("999", "external", "artist")));
            for (int size : List.of(1, 10, 20)) {
                var page = search.search("song", "ITUNES", null, size);
                assertThat(page.source()).isEqualTo(count > 20 ? "DB" : "ITUNES");
                int total = page.items().size();
                if (count <= 20) {
                    assertThat(page.items()).extracting(MusicItem::external_music_id).containsExactly("999");
                    assertThat(page.items().getFirst().music_id()).isNull();
                }
                while (page.has_next()) {
                    page = search.search("song", "ITUNES", page.next_cursor(), size);
                    assertThat(page.source()).isEqualTo("DB");
                    total += page.items().size();
                }
                assertThat(total).isEqualTo(count > 20 ? count : 1);
            }
        }
    }

    @Test
    void firstPageCacheHitSubsequentPageEmptyAndFailureLeaveBothTablesIdentical() throws Exception {
        long id = repository.insertSelectedMusic(item("1", "original", "stored artist")).music_id();
        jdbc.update("UPDATE music SET youtube_video_id='abcdefghijk', preview_url='stored-preview', "
                + "created_at='2026-01-01 00:00:00.123456', updated_at='2026-02-02 01:02:03.654321' WHERE id=?", id);
        repository.saveRecord(1, id, repository.findLocation(1, 2, 1).orElseThrow(), "place", "memo", Instant.now());
        var before = tables();
        var external = IntStream.rangeClosed(1, 25).mapToObj(n -> item("" + n, "external " + n, "artist")).toList();
        when(itunes.search("external")).thenReturn(external);
        var first = search.search("external", "ITUNES", null, 20);
        assertThat(tables()).isEqualTo(before);
        assertThat(first.items().getFirst()).isEqualTo(external.getFirst());
        assertThat(search.search("external", "ITUNES", null, 20)).isEqualTo(first);
        assertThat(tables()).isEqualTo(before);
        verify(itunes, org.mockito.Mockito.times(1)).search("external");
        var next = search.search("external", "ITUNES", first.next_cursor(), 20);
        assertThat(next.items()).hasSize(5);
        assertThat(tables()).isEqualTo(before);
        when(itunes.search("empty")).thenReturn(List.of());
        assertThat(search.search("empty", "ITUNES", null, 20).items()).isEmpty();
        assertThat(tables()).isEqualTo(before);
        when(itunes.search("failure")).thenThrow(new MusicRecordException("provider",
                org.springframework.http.HttpStatus.BAD_GATEWAY, "music provider unavailable"));
        assertThatThrownBy(() -> search.search("failure", "ITUNES", null, 20)).hasMessage("music provider unavailable");
        assertThat(tables()).isEqualTo(before);
        assertThatThrownBy(() -> Class.forName("com.muse.meomuneum.musicrecord.service.MusicSearchStorageService"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.muse.meomuneum.musicrecord.config.MusicSearchStorageConfig"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void externalSnapshotSurvivesThresholdChangeAndNewSearchUsesDbAndLostSnapshotIsGone() {
        for (int i = 1; i <= 20; i++) {
            repository.insertSelectedMusic(item("" + i, "song", "artist"));
        }
        when(itunes.search("song")).thenReturn(IntStream.rangeClosed(1, 25)
                .mapToObj(i -> item("" + i, "song external", "external artist")).toList());
        var first = search.search("song", "ITUNES", null, 20);
        when(itunes.lookup("21")).thenReturn(Optional.of(item("21", "song", "artist")));
        registration(itunes, cache).register(new Request("21"));
        assertThat(search.search("song", "ITUNES", first.next_cursor(), 20).source()).isEqualTo("ITUNES");
        assertThat(search.search("song", "ITUNES", null, 20).source()).isEqualTo("DB");
        var restarted = search(itunes, new MusicSearchCandidateCache(Clock.systemUTC()));
        assertThatThrownBy(() -> restarted.search("song", "ITUNES", first.next_cursor(), 20))
                .isInstanceOfSatisfying(MusicRecordException.class,
                        failure -> assertThat(failure.status()).isEqualTo(org.springframework.http.HttpStatus.GONE));
    }

    @Test
    void eightIndependentServicesConvergeOnOneInsertAndReuseUntouchedExistingFields() throws Exception {
        var barrier = new CyclicBarrier(8);
        var rollbacks = new AtomicInteger();
        when(itunes.lookup("123")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            barrier.await(10, TimeUnit.SECONDS);
            return Optional.of(item("123", "title", "artist"));
        });
        var manager = new DataSourceTransactionManager(dataSource) {
            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                rollbacks.incrementAndGet();
                super.doRollback(status);
            }
        };
        var results = new ArrayList<Response>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<Response>>();
            for (int i = 0; i < 8; i++) {
                var service = new SongRegistrationService(new MusicRecordRepository(jdbc), itunes,
                        new MusicSearchCandidateCache(Clock.systemUTC()), manager);
                futures.add(executor.submit(() -> service.register(new Request("123"))));
            }
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
        }
        assertThat(results.stream().filter(Response::created)).hasSize(1);
        assertThat(results.stream().map(result -> result.music().music_id()).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music", Integer.class)).isEqualTo(1);
        assertThat(rollbacks.get()).isEqualTo(7);
        jdbc.update("UPDATE music SET title='authoritative', artist_name='saved artist', "
                + "album_cover_url='stored-cover', preview_url='stored-preview', youtube_video_id='abcdefghijk', "
                + "created_at='2026-01-01 00:00:00.123456', updated_at='2026-02-02 01:02:03.654321'");
        var before = tables();
        var reused = registration(itunes, cache).register(new Request("123"));
        assertThat(reused.created()).isFalse();
        assertThat(reused.music().title()).isEqualTo("authoritative");
        assertThat(reused.music().is_queueable()).isFalse();
        assertThat(tables()).isEqualTo(before);
        verify(itunes, org.mockito.Mockito.times(8)).lookup("123");
    }

    @Test
    void registeredSongIdentityIsReusedByRecordCreateAndReplacementWithoutLookupOrCatalogWrites() {
        when(itunes.lookup("123")).thenReturn(Optional.of(item("123", "title", "artist")));
        when(itunes.lookup("456")).thenReturn(Optional.of(item("456", "replacement", "artist")));
        var service = registration(itunes, cache);
        var first = service.register(new Request("123"));
        var second = service.register(new Request("456"));
        var musicBefore = jdbc.queryForList("SELECT * FROM music ORDER BY id");
        org.mockito.Mockito.clearInvocations(itunes);
        var tokens = mock(LocationResolutionTokenProvider.class);
        when(tokens.validate("token", 1L)).thenReturn(new LocationResolutionClaims(1L, 1L, "11", 2L, "11440", 1L,
                Instant.now().plusSeconds(300)));
        var records = new MusicRecordService(repository, itunes, tokens, new MusicSearchCursorCodec("secret"), cache);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var created = transaction.execute(status -> records.create(1,
                new CreateRequest(new MusicSelection("ITUNES", "123"), "token", "place", "memo")));
        assertThat(repository.findRecord(1, created.record_id()).orElseThrow().music().music_id())
                .isEqualTo(first.music().music_id());
        transaction.execute(status -> records.update(1, created.record_id(), new ObjectMapper().readTree(
                "{\"music\":{\"provider\":\"ITUNES\",\"external_music_id\":\"456\"}}")));
        assertThat(repository.findRecord(1, created.record_id()).orElseThrow().music().music_id())
                .isEqualTo(second.music().music_id());
        assertThat(jdbc.queryForList("SELECT * FROM music ORDER BY id")).isEqualTo(musicBefore);
        verify(itunes, never()).lookup(any());
    }

    @Test
    void unicodeWhitespaceTokensAndLiteralLikeCharactersMatchInMysql() {
        repository.insertSelectedMusic(item("1", "밤\u00a0편지", "아이\u2003유"));
        repository.insertSelectedMusic(item("2", "100%_! 노래", "artist"));
        repository.insertSelectedMusic(item("3", "100percent other", "artist"));
        long max = repository.highestMusicId();
        for (String query : List.of("밤편지", "밤 편지", "아이유 밤편지")) {
            assertThat(repository.searchMusic(query, Long.MAX_VALUE, max, 21))
                    .extracting(MusicItem::external_music_id).containsExactly("1");
        }
        for (String query : List.of("%", "_", "!")) {
            assertThat(repository.searchMusic(query, Long.MAX_VALUE, max, 21))
                    .extracting(MusicItem::external_music_id).containsExactly("2");
        }
        when(itunes.search("밤 편지")).thenReturn(List.of(item("999", "external", "artist")));
        assertThat(search.search("  밤\u2003 편지  ", "ITUNES", null, 20).source()).isEqualTo("ITUNES");
    }

    @Test
    void malformedHttpSearchFails502WithoutPartialSnapshotCandidateOrEitherTableMutation() throws Exception {
        long musicId = repository.insertSelectedMusic(item("777", "original", "artist")).music_id();
        repository.saveRecord(1, musicId, repository.findLocation(1, 2, 1).orElseThrow(), "place", "memo",
                Instant.now());
        var before = tables();
        String valid = "{\"kind\":\"SoNg\",\"trackId\":123,\"trackName\":\"title\",\"artistName\":\"artist\"}";
        var body = new AtomicReference<>("{}");
        var lookups = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.createContext("/lookup", exchange -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            lookups.incrementAndGet();
            byte[] bytes = ("{\"results\":[" + valid + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            var http = new ItunesMusicSearchClient(new ObjectMapper(), "http://127.0.0.1:"
                    + server.getAddress().getPort() + "/search", "KR", Duration.ofSeconds(2));
            var realSearch = search(http, cache);
            var users = mock(CurrentUserResolver.class);
            var mvc = MockMvcBuilders.standaloneSetup(new MusicRecordController(realSearch, users))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();
            var invalidSongs = List.of(valid.replace("123", "0"), valid.replace("123", "-1"),
                    valid.replace("123", "\"123\""), valid.replace("123", "1.5"),
                    valid.replace("123", "9223372036854775808"), valid.replace("\"trackId\":123,", ""),
                    valid.replace("\"trackName\":\"title\",", ""), valid.replace("\"title\"", "42"),
                    valid.replace("title", "x".repeat(256)), valid.replace(",\"artistName\":\"artist\"", ""),
                    valid.replace("\"artist\"", "null"), valid.replace("\"artist\"", "\"" + "x".repeat(1001) + "\""));
            var invalidBodies = new ArrayList<>(List.of("{", "{\"results\":{}}", "null"));
            for (String invalid : invalidSongs) {
                invalidBodies.add("{\"results\":[" + valid + "," + invalid + "]}");
            }
            int attempt = 0;
            for (String invalidBody : invalidBodies) {
                body.set(invalidBody);
                String query = "malformed" + attempt++;
                mvc.perform(get("/api/v1/music/search").param("query", query)).andExpect(status().isBadGateway());
                assertThat(cache.findCandidate("KR", "123")).isEmpty();
                assertThat(cache.findQuery("3:KR:" + new MusicSearchCursorCodec("secret").hash(query))).isEmpty();
                assertThat(tables()).isEqualTo(before);
            }
            assertThat(lookups).hasValue(0);
            registration(http, cache).register(new Request("123"));
            assertThat(lookups).hasValue(2);
            body.set("{\"results\":[{\"kind\":\"podcast\",\"trackId\":0}," + valid + "]}");
            assertThat(realSearch.search("mixed", "ITUNES", null, 20).items())
                    .extracting(MusicItem::external_music_id).containsExactly("123");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedLookupOutsideWriteTransactionNeverMutatesEitherTable() throws Exception {
        long id = repository.insertSelectedMusic(item("777", "original", "artist")).music_id();
        repository.saveRecord(1, id, repository.findLocation(1, 2, 1).orElseThrow(), "place", "memo", Instant.now());
        var before = tables();
        String valid = "{\"results\":[{\"kind\":\"song\",\"trackId\":123,"
                + "\"trackName\":\"title\",\"artistName\":\"artist\"}]}";
        var body = new AtomicReference<>(valid);
        var status = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lookup", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            var http = new ItunesMusicSearchClient(new ObjectMapper(), "http://127.0.0.1:"
                    + server.getAddress().getPort() + "/search", "KR", Duration.ofSeconds(2)) {
                @Override
                public Optional<MusicItem> lookup(String trackId) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return super.lookup(trackId);
                }
            };
            var users = mock(CurrentUserResolver.class);
            when(users.resolve(any())).thenReturn(1L);
            var mvc = MockMvcBuilders.standaloneSetup(new com.muse.meomuneum.musicrecord.controller.SongController(
                    registration(http, cache), users)).setControllerAdvice(new GlobalExceptionHandler()).build();
            for (String response : List.of(valid.replace("123", "124"), valid.replace("song", "podcast"))) {
                body.set(response);
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/songs")
                        .contentType("application/json").content("{\"externalTrackId\":\"123\"}"))
                        .andExpect(status().isNotFound());
                assertThat(tables()).isEqualTo(before);
            }
            for (String response : List.of("{", valid.replace("title", "x".repeat(256)),
                    valid.replace("\"artist\"", "\"" + "x".repeat(1001) + "\""))) {
                body.set(response);
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/songs")
                        .contentType("application/json").content("{\"externalTrackId\":\"123\"}"))
                        .andExpect(status().isBadGateway());
                assertThat(tables()).isEqualTo(before);
            }
            status.set(503);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/songs")
                    .contentType("application/json").content("{\"externalTrackId\":\"123\"}"))
                    .andExpect(status().isBadGateway());
            assertThat(tables()).isEqualTo(before);
        } finally {
            server.stop(0);
        }
    }

    private SongRegistrationService registration(ItunesMusicSearchClient client, MusicSearchCandidateCache candidates) {
        return new SongRegistrationService(repository, client, candidates,
                new DataSourceTransactionManager(dataSource));
    }

    private MusicRecordService search(ItunesMusicSearchClient client, MusicSearchCandidateCache candidates) {
        return new MusicRecordService(repository, client, mock(LocationResolutionTokenProvider.class),
                new MusicSearchCursorCodec("secret"), candidates);
    }

    private Map<String, List<Map<String, Object>>> tables() {
        return Map.of("music", jdbc.queryForList("SELECT * FROM music ORDER BY id"),
                "music_records", jdbc.queryForList("SELECT * FROM music_records ORDER BY id"));
    }

    private MusicItem releaseItem(int externalId, Long release) {
        return new MusicItem(null, "ITUNES", Integer.toString(externalId), "release test", "artist",
                null, null, null, false, StorefrontNames.EMPTY, release);
    }

    private MusicItem item(String id, String title, String artist) {
        return new MusicItem(null, "ITUNES", id, title, artist, null, null, null, false);
    }
}
