package com.muse.meomuneum.chat.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class YouTubeMusicVideoProviderTest {
    private static final String VIDEO = """
            {"id":"ABCDEFGHIJK","snippet":{"channelId":"UCsynthetic","title":"Synthetic video",
              "channelTitle":"Synthetic channel","liveBroadcastContent":"none"},
             "status":{"privacyStatus":"public","uploadStatus":"processed","embeddable":true},
             "contentDetails":{"duration":"PT3M","regionRestriction":{"allowed":["KR"]}}}
            """;
    private HttpServer server;
    private ChatMusicVideoBudget budget;
    private YouTubeMusicVideoProvider provider;
    private final AtomicReference<String> response = new AtomicReference<>("{\"items\":[" + VIDEO + "]}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final List<String> queries = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            String body = exchange.getRequestURI().getPath().endsWith("search")
                    ? "{\"items\":[{\"id\":{\"videoId\":\"ABCDEFGHIJK\"}},{\"id\":{\"videoId\":\"ABCDEFGHIJK\"}}]}"
                    : response.get();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        budget = mock(ChatMusicVideoBudget.class);
        provider = new YouTubeMusicVideoProvider(HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                "synthetic_secret", budget, 1, 75, 500);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void searchesOnceThenBatchVerifiesDeduplicatedVideosWithKrAndEmbedFilters() {
        var result = provider.search("곡 제목", "Artist");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().durationMillis()).isEqualTo(180000);
        assertThat(queries).hasSize(2);
        assertThat(queries.getFirst()).contains("type=video", "maxResults=10", "regionCode=KR", "videoEmbeddable=true",
                "videoSyndicated=true");
        assertThat(queries.getLast()).contains("part=snippet,contentDetails,status", "id=ABCDEFGHIJK");
        verify(budget).reserve("provider-day-search", 86400, 75);
        verify(budget).reserve("provider-day-videos", 86400, 500);
    }

    @Test
    void onlySuccessfulEmptyResponseMeansUnavailableAndProviderFailureNeverBecomesEmpty() {
        response.set("{\"items\":[]}");
        assertThat(provider.verify("ABCDEFGHIJK")).isNull();
        status.set(403);
        assertThatThrownBy(() -> provider.search("Song", "Artist"))
                .isInstanceOfSatisfying(ChatMusicException.class, error -> {
                    assertThat(error.status()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("VIDEO_SEARCH_UNAVAILABLE");
                    assertThat(error.toString()).doesNotContain("synthetic_secret", "http", "403");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void oversizedMalformedAndErrorResponsesFailClosed() {
        for (String body : List.of(" ".repeat(262145), "not json", "{}", "{\"items\":[],\"error\":{}}")) {
            response.set(body);
            assertThatThrownBy(() -> provider.verify("ABCDEFGHIJK")).isInstanceOf(ChatMusicException.class);
        }
    }

    @Test
    void excludesLivePrivateAgeRestrictedKrBlockedAndUnknownDuration() {
        for (String body : List.of(VIDEO.replace("\"none\"", "\"live\""),
                VIDEO.replace("\"none\"", "\"upcoming\""), VIDEO.replace("\"public\"", "\"private\""),
                VIDEO.replace("\"processed\"", "\"uploaded\""),
                VIDEO.replace("\"embeddable\":true", "\"embeddable\":false"),
                VIDEO.replace("\"allowed\":[\"KR\"]", "\"blocked\":[\"KR\"]"),
                VIDEO.replace("\"allowed\":[\"KR\"]", "\"allowed\":[\"US\"]"),
                VIDEO.replace("\"duration\":\"PT3M\"", "\"duration\":\"PT0S\""),
                VIDEO.replace("\"duration\":\"PT3M\"", "\"duration\":\"unknown\""),
                VIDEO.replace("\"duration\":\"PT3M\"",
                        "\"duration\":\"PT3M\",\"contentRating\":{\"ytRating\":\"ytAgeRestricted\"}"))) {
            assertThat(YouTubeMusicVideoProvider.playable(new ObjectMapper().readTree(body))).isNull();
        }
    }
}
