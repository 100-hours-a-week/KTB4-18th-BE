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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import tools.jackson.databind.ObjectMapper;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.CreateRequest;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicSelection;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository.Location;
import com.muse.meomuneum.musicrecord.service.LocationTokenService;
import com.muse.meomuneum.musicrecord.service.MusicRecordService;
import com.muse.meomuneum.musicrecord.service.MusicSearchCursorCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class ItunesMusicSearchClientHttpTest {
    private static final String TRACK_BODY = "{\"results\":[{\"trackId\":123,"
            + "\"trackName\":\"테스트 노래\",\"artistName\":\"테스트 가수\","
            + "\"artworkUrl100\":\"https://example.test/cover\","
            + "\"previewUrl\":\"https://example.test/preview\"}]}";

    private HttpServer server;
    private ItunesMusicSearchClient client;
    private final AtomicInteger searchStatus = new AtomicInteger(200);
    private final AtomicInteger lookupStatus = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> respond(exchange, searchStatus.get(), TRACK_BODY));
        server.createContext("/lookup", exchange -> respond(exchange, lookupStatus.get(), TRACK_BODY));
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
    void searchTransportFailureIs502ButCreateLookupFailureIsContract500() {
        searchStatus.set(503);
        lookupStatus.set(503);
        MusicRecordRepository repository = mock(MusicRecordRepository.class);
        LocationTokenService tokens = mock(LocationTokenService.class);
        MusicRecordService service = new MusicRecordService(repository, client, tokens,
                mock(KakaoReverseGeocodingClient.class), new MusicSearchCursorCodec("test-only-secret"));
        when(repository.highestMusicId()).thenReturn(0L);
        when(repository.searchMusic("테스트", Long.MAX_VALUE, 0L, 21)).thenReturn(List.of());
        when(tokens.parse("location-token", 1L)).thenReturn(
                new LocationTokenService.LocationClaims(3L, 4L, 5L, Instant.now().plusSeconds(300)));
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
