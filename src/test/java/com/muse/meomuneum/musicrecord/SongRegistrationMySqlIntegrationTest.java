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
                "V20260921200528__create_users_table.sql", "V20260923142421__create_chat_room_foundation.sql",
                "V20260925174748__create_map_dots_and_music_records_tables.sql")) {
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
        assertThat(reused.music().is_queueable()).isTrue();
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
            registration(http, cache).register(new Request("123"));
            assertThat(lookups).hasValue(1);
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

    private MusicItem item(String id, String title, String artist) {
        return new MusicItem(null, "ITUNES", id, title, artist, null, null, null, false);
    }
}
