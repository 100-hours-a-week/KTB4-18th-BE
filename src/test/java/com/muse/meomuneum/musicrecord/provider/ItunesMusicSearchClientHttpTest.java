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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.muse.meomuneum.location.security.LocationResolutionClaims;
import com.muse.meomuneum.location.security.LocationResolutionTokenProvider;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateRequest;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSelection;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository.Location;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;
import com.muse.meomuneum.recommendation.provider.ItunesRecommendationProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class ItunesMusicSearchClientHttpTest {
    private static final String TRACK_BODY = "{\"results\":[{\"trackId\":123,"
            + "\"trackName\":\"테스트 노래\",\"artistName\":\"테스트 가수\","
            + "\"artworkUrl100\":\"https://example.test/cover\","
            + "\"previewUrl\":\"https://example.test/preview\"}]}";

    private HttpServer server;
    private ItunesMusicSearchClient client;
    private final AtomicInteger searchStatus = new AtomicInteger(200);
    private final AtomicInteger lookupStatus = new AtomicInteger(200);
    private final List<String> configuredQueries = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> respond(exchange, searchStatus.get(), TRACK_BODY));
        server.createContext("/lookup", exchange -> respond(exchange, lookupStatus.get(), TRACK_BODY));
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
    }

    @Test
    void musicSearchAndRecommendationShareConfiguredCountryAndUrl() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/configured/search";
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ItunesMusicSearchClient.class)
                .withBean(ItunesRecommendationProvider.class)
                .withPropertyValues("recommendation.itunes.search-url=" + url,
                        "recommendation.itunes.country=KR", "recommendation.itunes.timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(ItunesMusicSearchClient.class).search("테스트");
                    context.getBean(ItunesRecommendationProvider.class).recommend(List.of("테스트"));
                });

        assertThat(configuredQueries).hasSize(2).allMatch(query -> query.contains("country=KR"));
    }

    @Test
    void musicSearchAndRecommendationRetainSharedDefaults() {
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
                new MusicSearchCursorCodec("test-only-secret"));
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

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
