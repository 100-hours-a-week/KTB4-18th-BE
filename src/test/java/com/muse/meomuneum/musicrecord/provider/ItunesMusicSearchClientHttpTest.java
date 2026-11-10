package com.muse.meomuneum.musicrecord.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.music.exception.MusicMetadataException;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateRequest;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSelection;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.StorefrontNames;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository.Location;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCandidateCache;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;
import com.muse.meomuneum.musicrecord.service.MusicSearchStorageService;
import com.muse.meomuneum.recommendation.provider.ItunesRecommendationProvider;
import com.muse.meomuneum.recommendation.provider.RecommendationCommand;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class ItunesMusicSearchClientHttpTest {
    private static final String TRACK = "{\"kind\":\"song\",\"trackId\":123,"
            + "\"trackName\":\"테스트 노래\",\"artistName\":\"테스트 가수\","
            + "\"artworkUrl100\":\"https://example.test/cover\","
            + "\"previewUrl\":\"https://example.test/preview\"}";
    private static final String KR_TRACK = TRACK.replace("테스트 노래", "한국 표기 곡")
            .replace("테스트 가수", "한국 표기 가수").replace("example.test", "kr.example.test");
    private static final String TRACK_BODY = resultsWithTracks(TRACK);
    private static final String KR_TRACK_BODY = resultsWithTracks(KR_TRACK);
    private static final String EMPTY_RESULTS = resultsWithTracks();

    private HttpServer server;
    private ItunesMusicSearchClient client;
    private final AtomicReference<String> trackBody = new AtomicReference<>(TRACK_BODY);
    private final AtomicInteger searchStatus = new AtomicInteger(200);
    private final AtomicInteger lookupStatus = new AtomicInteger(200);
    private final AtomicReference<String> localizationBody = new AtomicReference<>(EMPTY_RESULTS);
    private final AtomicInteger localizationStatus = new AtomicInteger(200);
    private final List<String> lookupQueries = new CopyOnWriteArrayList<>();
    private final List<String> configuredQueries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> respond(exchange, searchStatus.get(), trackBody.get()));
        server.createContext("/lookup", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            lookupQueries.add(query);
            if (query.contains("lang=ko_kr")) {
                respond(exchange, localizationStatus.get(), localizationBody.get());
            } else {
                respond(exchange, lookupStatus.get(), trackBody.get());
            }
        });
        server.createContext("/configured/search", exchange -> {
            configuredQueries.add(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, TRACK_BODY);
        });
        server.createContext("/slow/search", exchange -> {
            try {
                Thread.sleep(500);
                respond(exchange, 200, TRACK_BODY);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        client = new ItunesMusicSearchClient(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/search",
                "US", Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void searchAndLookupParseLocalHttpResponsesWithoutApiKey() {
        var searched = client.search("테스트 노래");
        var lookedUp = client.lookup("123");

        assertThat(searched).hasSize(1);
        assertThat(searched.getFirst().external_music_id()).isEqualTo("123");
        assertThat(lookedUp).isPresent();
        assertThat(lookedUp.orElseThrow().title()).isEqualTo("테스트 노래");
        assertThat(lookupQueries).containsExactly("id=123&country=KR&lang=ko_kr&entity=song&limit=200",
                "id=123&country=US&entity=song", "id=123&country=KR&lang=ko_kr&entity=song&limit=200");
    }

    @Test
    void searchPagesFollowPrimaryReleaseDatesWithStableTiesAndUnknownsLast() {
        trackBody.set(resultsWithTracks(trackWithRelease("123", "malformed"),
                trackWithRelease("124", "2012-10-05T10:00:00Z"),
                trackWithRelease("125", "2024-06-07T01:00:00Z"),
                trackWithRelease("126", "2024-06-07T10:00:00+09:00"), TRACK.replace("123", "127")));
        localizationBody.set(resultsWithTracks(
                KR_TRACK.replace("123", "125").replace("}", ",\"releaseDate\":\"2000-01-01T00:00:00Z\"}")));
        var repository = mock(MusicRecordRepository.class);
        when(repository.searchMusic("query", Long.MAX_VALUE, 0L, 21)).thenReturn(List.of());
        var cache = new MusicSearchCandidateCache(java.time.Clock.systemUTC());
        var service = new MusicRecordService(repository, client, mock(LocationResolutionTokenProvider.class),
                new MusicSearchCursorCodec("test-only-secret"), cache,
                mock(MusicSearchStorageService.class));

        var first = service.search("query", "ITUNES", null, 2);
        assertThat(first.items()).extracting(MusicItem::external_music_id).containsExactly("125", "126");
        assertThat(first.items().getFirst().title()).isEqualTo("한국 표기 곡");
        assertThat(new ObjectMapper().valueToTree(first.items().getFirst()).size()).isEqualTo(9);
        assertThat(cache.findCandidate("US", "125").orElseThrow().releaseEpochSecond())
                .isEqualTo(Instant.parse("2024-06-07T01:00:00Z").getEpochSecond());
        var second = service.search("query", "ITUNES", first.next_cursor(), 2);
        assertThat(second.items()).extracting(MusicItem::external_music_id).containsExactly("124", "123");
        var third = service.search("query", "ITUNES", second.next_cursor(), 2);
        assertThat(third.items()).extracting(MusicItem::external_music_id).containsExactly("127");
        assertThat(third.has_next()).isFalse();
    }

    @Test
    void searchRanksMatchingKoreanAndUsNamesBeforeNewerFuzzyResultsAcrossPages() {
        trackBody.set(resultsWithTracks(trackWithRelease("123", "2026-11-09T10:00:00Z"),
                trackWithRelease("124", "2012-10-05T10:00:00Z"),
                trackWithRelease("125", "2024-06-07T01:00:00Z").replace("테스트 가수", "HANRORO"),
                trackWithRelease("126", "2024-06-07T10:00:00+09:00").replace("테스트 노래", "한로로 라이브"),
                trackWithRelease("127", "2025-07-07T10:00:00Z").replace("테스트 가수", "밴드 한로로"),
                TRACK.replace("123", "128")));
        localizationBody.set(resultsWithTracks(KR_TRACK.replace("123", "124").replace("한국 표기 곡", "한로로"),
                KR_TRACK.replace("123", "125").replace("한국 표기 가수", "한 로 로"),
                KR_TRACK.replace("123", "128").replace("한국 표기 가수", "한로로")));
        var repository = mock(MusicRecordRepository.class);
        when(repository.searchMusic("한로로", Long.MAX_VALUE, 0L, 21)).thenReturn(List.of());
        when(repository.searchMusic("han roro", Long.MAX_VALUE, 0L, 21)).thenReturn(List.of());
        var cache = new MusicSearchCandidateCache(java.time.Clock.systemUTC());
        var service = new MusicRecordService(repository, client, mock(LocationResolutionTokenProvider.class),
                new MusicSearchCursorCodec("test-only-secret"), cache,
                mock(MusicSearchStorageService.class));

        var first = service.search("한로로", "ITUNES", null, 2);
        assertThat(first.items()).extracting(MusicItem::external_music_id).containsExactly("125", "124");
        assertThat(first.items().getFirst().artist_name()).isEqualTo("한 로 로");
        var second = service.search("한로로", "ITUNES", first.next_cursor(), 2);
        assertThat(second.items()).extracting(MusicItem::external_music_id).containsExactly("128", "127");
        var third = service.search("한로로", "ITUNES", second.next_cursor(), 2);
        assertThat(third.items()).extracting(MusicItem::external_music_id).containsExactly("126", "123");
        assertThat(third.has_next()).isFalse();

        var usAlias = service.search("han roro", "ITUNES", null, 2);
        assertThat(usAlias.items()).extracting(MusicItem::external_music_id).containsExactly("125", "123");
        assertThat(usAlias.items().getFirst().artist_name()).isEqualTo("한 로 로");
    }

    private static String trackWithRelease(String id, String release) {
        return TRACK.replace("123", id).replace("}", ",\"releaseDate\":\"" + release + "\"}");
    }

    @Test
    void searchLocalizesNamesByTrackIdWhilePreservingOrderAndMedia() {
        trackBody.set(resultsWithTracks(TRACK, TRACK.replace("123", "124"), TRACK.replace("123", "125")));
        localizationBody.set(resultsWithTracks(KR_TRACK.replace("123", "125").replace("한국 표기 곡", "다른 곡"),
                KR_TRACK.replace("123", "999"), KR_TRACK));

        assertThat(client.search("query")).containsExactly(
                new MusicItem(null, "ITUNES", "123", "한국 표기 곡", "한국 표기 가수",
                        "https://example.test/cover", "https://example.test/preview", null, false,
                        names("한국 표기 곡", "한국 표기 가수", false)),
                new MusicItem(null, "ITUNES", "124", "테스트 노래", "테스트 가수",
                        "https://example.test/cover", "https://example.test/preview", null, false,
                        names(null, null, false)),
                new MusicItem(null, "ITUNES", "125", "다른 곡", "한국 표기 가수",
                        "https://example.test/cover", "https://example.test/preview", null, false,
                        names("다른 곡", "한국 표기 가수", false)));
        assertThat(lookupQueries).containsExactly("id=123,124,125&country=KR&lang=ko_kr&entity=song&limit=200");
    }

    @Test
    void emptySearchSkipsLocalization() {
        trackBody.set(EMPTY_RESULTS);

        assertThat(client.search("query")).isEmpty();
        assertThat(lookupQueries).isEmpty();
    }

    @Test
    void failedOrMalformedLocalizationPreservesSearchResults() {
        localizationBody.set(KR_TRACK_BODY);
        localizationStatus.set(503);
        var fallback = client.search("query").getFirst();
        assertThat(fallback.title()).isEqualTo("테스트 노래");
        assertThat(fallback.artist_name()).isEqualTo("테스트 가수");
        assertThat(fallback.storefrontNames()).isEqualTo(names(null, null, true));

        localizationStatus.set(200);
        for (String invalid : List.of("not JSON", "{\"results\":{}}")) {
            localizationBody.set(invalid);
            assertThat(client.search("query")).containsExactly(fallback);
        }

        localizationBody.set(KR_TRACK_BODY);
        assertThat(client.retryKoreanLookup(fallback).storefrontNames())
                .isEqualTo(names("한국 표기 곡", "한국 표기 가수", false));
    }

    @Test
    void directLookupStoresEachValidKoreanFieldWithoutChangingThePublicJson() {
        localizationBody.set(KR_TRACK_BODY.replace("한국 표기 곡", " "));
        var item = client.lookup("123").orElseThrow();

        assertThat(item.title()).isEqualTo("테스트 노래");
        assertThat(item.artist_name()).isEqualTo("한국 표기 가수");
        assertThat(item.storefrontNames()).isEqualTo(names(null, "한국 표기 가수", false));
        var json = new ObjectMapper().valueToTree(item);
        assertThat(json.size()).isEqualTo(9);
        assertThat(json.has("storefrontNames")).isFalse();
    }

    private StorefrontNames names(String titleKo, String artistNameKo, boolean retry) {
        return new StorefrontNames("테스트 노래", "테스트 가수", titleKo, artistNameKo, retry);
    }

    @Test
    void localizationTimesOutWhenResponseBodyStallsAfterHeaders() {
        var releaseBody = new CountDownLatch(1);
        var sentHeaders = new CountDownLatch(1);
        server.createContext("/slow-body/search", exchange -> respond(exchange, 200, TRACK_BODY));
        server.createContext("/slow-body/lookup", exchange -> {
            byte[] bytes = KR_TRACK_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            sentHeaders.countDown();
            try (var body = exchange.getResponseBody()) {
                releaseBody.await(6, TimeUnit.SECONDS);
                body.write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        var slowClient = new ItunesMusicSearchClient(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/slow-body/search",
                "US", Duration.ofSeconds(8));

        try {
            long start = System.nanoTime();
            assertThat(slowClient.search("query").getFirst().title()).isEqualTo("테스트 노래");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
            assertThat(sentHeaders.getCount()).isZero();
        } finally {
            releaseBody.countDown();
        }
    }

    private static String resultsWithTracks(String... tracks) {
        return "{\"results\":[" + String.join(",", tracks) + "]}";
    }

    @Test
    void searchAndLookupResizeBothSupportedArtworkFilenames() {
        assertArtworkMapping("https://example.test/cover/100x100bb.jpg",
                "https://example.test/cover/680x680bb.jpg");
        assertArtworkMapping("https://example.test/cover.100x100-75.jpg",
                "https://example.test/cover.680x680-75.jpg");
    }

    @Test
    void artworkResizePreservesRawUrlComponents() {
        assertArtworkMapping("https://example.test/100x100bb.jpg?#", "https://example.test/680x680bb.jpg?#");
        assertArtworkMapping("https://example.test/cover.100x100.100x100-75.jpg",
                "https://example.test/cover.100x100.680x680-75.jpg");
        assertArtworkMapping("https://example.test/100x100/encoded%20cover/100x100bb.jpg"
                + "?size=100x100&x=%2F#100x100",
                "https://example.test/100x100/encoded%20cover/680x680bb.jpg?size=100x100&x=%2F#100x100");
        assertArtworkMapping("HTTPS://example.test/100x100bb.jpg/encoded%2Fcover.100x100-75.jpg"
                + "?size=100x100#100x100",
                "HTTPS://example.test/100x100bb.jpg/encoded%2Fcover.680x680-75.jpg?size=100x100#100x100");
    }

    @Test
    void artworkMappingPreservesUnsupportedOrInvalidUrls() {
        for (String artwork : List.of("", "https://example.test/cover", "https://example.test/600x600bb.jpg",
                "https://example.test/100x100bb.png", "https://example.test/100x100cc.jpg",
                "https://example.test/cover.100x100-80.jpg", "https://example.test/100x100bb.jpg/cover",
                "https://example.test/cover?file=100x100bb.jpg#cover.100x100-75.jpg",
                "https://example.test/bad space/100x100bb.jpg", "https://example.test/%ZZ/100x100bb.jpg",
                "/cover/100x100bb.jpg", "https:/cover/100x100bb.jpg", "file:///cover/100x100bb.jpg",
                "ftp://example.test/100x100bb.jpg", "data:image/jpeg,100x100bb.jpg")) {
            assertArtworkMapping(artwork, artwork);
        }
    }

    @Test
    void missingNullAndNontextArtworkRemainNull() {
        trackBody.set(TRACK_BODY.replace("\"artworkUrl100\":\"https://example.test/cover\",", ""));
        assertArtworkInSearchAndLookup(null);
        for (String artwork : List.of("null", "123", "true", "{}", "[]")) {
            setArtworkJson(artwork);
            assertArtworkInSearchAndLookup(null);
        }
    }

    private void assertArtworkMapping(String artwork, String expected) {
        setArtworkJson(new ObjectMapper().writeValueAsString(artwork));
        assertArtworkInSearchAndLookup(expected);
    }

    private void setArtworkJson(String artworkJson) {
        trackBody.set(TRACK_BODY.replace("\"https://example.test/cover\"", artworkJson));
    }

    private void assertArtworkInSearchAndLookup(String expected) {
        var searched = client.search("테스트 노래").getFirst();
        var lookedUp = client.lookup("123").orElseThrow();
        assertThat(searched.album_cover_url()).isEqualTo(expected);
        assertThat(lookedUp.album_cover_url()).isEqualTo(expected);
        assertThat(searched.external_music_id()).isEqualTo("123");
        assertThat(lookedUp.external_music_id()).isEqualTo("123");
        assertThat(searched.provider()).isEqualTo("ITUNES");
        assertThat(searched.title()).isEqualTo("테스트 노래");
        assertThat(searched.artist_name()).isEqualTo("테스트 가수");
        assertThat(searched.preview_url()).isEqualTo("https://example.test/preview");
    }

    @Test
    void lookupRejectsMalformedMetadataAsProviderFailureWithoutTruncation() {
        server.createContext("/metadata/lookup", exchange -> respond(exchange, 200,
                TRACK_BODY.replace("테스트 가수", "가".repeat(1001))));
        var metadataClient = new ItunesMusicSearchClient(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/metadata/search",
                "US", Duration.ofSeconds(2));
        assertThatThrownBy(() -> metadataClient.lookup("123"))
                .isInstanceOf(MusicMetadataException.class).hasMessage("artist_name_too_long");
    }

    @Test
    void musicSearchCountryIsIndependentFromRecommendationCountry() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/configured/search";
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ItunesMusicSearchClient.class)
                .withBean(ItunesRecommendationProvider.class)
                .withPropertyValues("recommendation.itunes.search-url=" + url,
                        "recommendation.itunes.country=US", "recommendation.itunes.timeout=2s",
                        "music.search.country=KR")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(ItunesMusicSearchClient.class).search("테스트");
                    context.getBean(ItunesRecommendationProvider.class)
                            .recommend(new RecommendationCommand(UUID.randomUUID(), UUID.randomUUID(), "테스트"));
                });

        assertThat(configuredQueries).hasSize(2);
        assertThat(configuredQueries.get(0)).contains("country=KR");
        assertThat(configuredQueries.get(1)).contains("country=US");
    }

    @Test
    void musicSearchDefaultsToUsAndRecommendationRetainsUs() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ItunesMusicSearchClient.class)
                .withBean(ItunesRecommendationProvider.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var music = context.getBean(ItunesMusicSearchClient.class);
                    var recommendation = context.getBean(ItunesRecommendationProvider.class);
                    assertThat(ReflectionTestUtils.getField(music, "country")).isEqualTo("US");
                    assertThat(ReflectionTestUtils.getField(recommendation, "country")).isEqualTo("US");
                    assertThat(ReflectionTestUtils.getField(music, "url"))
                            .isEqualTo("https://itunes.apple.com/search");
                    assertThat(ReflectionTestUtils.getField(recommendation, "searchUrl"))
                            .isEqualTo("https://itunes.apple.com/search");
                    assertThat(ReflectionTestUtils.getField(music, "timeout")).isEqualTo(Duration.ofSeconds(8));
                    assertThat(ReflectionTestUtils.getField(recommendation, "timeout"))
                            .isEqualTo(Duration.ofSeconds(8));
                });
    }

    @Test
    void musicSearchUsesConfiguredTimeout() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow/search";
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ItunesMusicSearchClient.class)
                .withPropertyValues("recommendation.itunes.search-url=" + url,
                        "recommendation.itunes.timeout=100ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatThrownBy(() -> context.getBean(ItunesMusicSearchClient.class).search("테스트"))
                            .isInstanceOf(MusicRecordException.class)
                            .satisfies(error -> assertThat(((MusicRecordException) error).status())
                                    .isEqualTo(HttpStatus.BAD_GATEWAY));
                });
    }

    @Test
    void searchTransportFailureIs502ButCreateLookupFailureIsContract500() {
        searchStatus.set(503);
        lookupStatus.set(503);
        MusicRecordRepository repository = mock(MusicRecordRepository.class);
        LocationResolutionTokenProvider tokens = mock(LocationResolutionTokenProvider.class);
        MusicRecordService service = new MusicRecordService(repository, client, tokens,
                new MusicSearchCursorCodec("test-only-secret"),
                new MusicSearchCandidateCache(java.time.Clock.systemUTC()),
                mock(MusicSearchStorageService.class));
        when(repository.highestMusicId()).thenReturn(0L);
        when(repository.searchMusic("테스트", Long.MAX_VALUE, 0L, 21)).thenReturn(List.of());
        when(tokens.validate("location-token", 1L)).thenReturn(
                new LocationResolutionClaims(1L, 5L, "11", 4L, "11440", 3L,
                        Instant.now().plusSeconds(300)));
        when(repository.findLocation(3L, 4L, 5L)).thenReturn(Optional.of(
                new Location(3L, "dot-3", 4L, "11440", "성동구", 5L, "11", "서울특별시")));
        when(repository.findMusic("ITUNES", "123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.search("테스트", "ITUNES", null, 20))
                .isInstanceOf(MusicRecordException.class)
                .satisfies(error -> {
                    MusicRecordException failure = (MusicRecordException) error;
                    assertThat(failure.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(failure.publicMessage()).isEqualTo("music provider unavailable");
                });
        assertThatThrownBy(() -> service.create(1L, new CreateRequest(
                new MusicSelection("ITUNES", "123"), "location-token", null, null)))
                .isInstanceOf(MusicRecordException.class)
                .satisfies(error -> {
                    MusicRecordException failure = (MusicRecordException) error;
                    assertThat(failure.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(failure.publicMessage()).isEqualTo("internal server error");
                });
    }

    @Test
    void lookupOnlyUsesMatchingSongsAndSearchRejectsMalformedSongsAtomically() {
        trackBody.set(TRACK_BODY.replace("123", "124"));
        assertThat(client.lookup("123")).isEmpty();
        trackBody.set(TRACK_BODY.replace("song", "podcast"));
        assertThat(client.lookup("123")).isEmpty();
        trackBody.set(TRACK_BODY.replace("song", "SoNg"));
        assertThat(client.lookup("123")).isPresent();
        for (String invalid : List.of(TRACK_BODY.replace("123", "0"), TRACK_BODY.replace("123", "1.25"),
                TRACK_BODY.replace("\"trackName\":\"테스트 노래\",", ""),
                TRACK_BODY.replace("테스트 노래", ""), TRACK_BODY.replace("테스트 가수", ""),
                TRACK_BODY.replace("테스트 노래", "가".repeat(256)))) {
            trackBody.set(invalid);
            assertThatThrownBy(() -> client.search("query"))
                    .isInstanceOfAny(MusicRecordException.class, MusicMetadataException.class);
        }
    }

    @Test
    void validSongResultsAreDeduplicatedAndCappedAt200() {
        String songs = java.util.stream.IntStream.rangeClosed(1, 205)
                .mapToObj(id -> "{\"kind\":\"song\",\"trackId\":" + id
                        + ",\"trackName\":\"title\",\"artistName\":\"artist\"}")
                .collect(java.util.stream.Collectors.joining(","));
        trackBody.set("{\"results\":[" + songs + ","
                + "{\"kind\":\"song\",\"trackId\":1,\"trackName\":\"duplicate\",\"artistName\":\"artist\"}]}");
        var results = client.search("query");
        assertThat(results).hasSize(200);
        assertThat(results.stream().map(MusicItem::external_music_id).distinct()).hasSize(200);
        assertThatThrownBy(results::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
