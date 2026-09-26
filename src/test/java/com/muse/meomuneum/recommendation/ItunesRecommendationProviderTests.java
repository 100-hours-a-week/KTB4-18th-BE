package com.muse.meomuneum.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.provider.ItunesRecommendationProvider;
import com.muse.meomuneum.recommendation.provider.RecommendationCommand;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class ItunesRecommendationProviderTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void searchesConversationAndReturnsDistinctPartialResults() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            String query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (!query.contains("비 오는 밤 드라이브")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            byte[] body = """
                    {"results":[
                      {"kind":"song","trackId":101,"trackName":"첫 곡","artistName":"가수","previewUrl":"https://example.test/1"},
                      {"kind":"song","trackId":101,"trackName":"첫 곡","artistName":"가수"},
                      {"kind":"song","trackId":104,"trackName":"첫 곡","artistName":"가수"},
                      {"kind":"song","trackId":102,"trackName":"둘째 곡","artistName":"가수"},
                      {"kind":"music-video","trackId":103,"trackName":"영상","artistName":"가수"}
                    ]}
                    """
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        var provider = new ItunesRecommendationProvider(new ObjectMapper(), searchUrl(), "US", Duration.ofSeconds(2));
        var tracks = provider.recommend(command("비 오는 밤 드라이브"));

        assertEquals(2, tracks.size());
        assertEquals("101", tracks.get(0).externalId());
        assertEquals("102", tracks.get(1).externalId());
        assertEquals("ITUNES", tracks.get(0).provider());
    }

    @Test
    void mapsUpstreamFailureToServiceUnavailable() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        var provider = new ItunesRecommendationProvider(new ObjectMapper(), searchUrl(), "US", Duration.ofSeconds(2));
        RecommendationException error = assertThrows(RecommendationException.class,
                () -> provider.recommend(command("노래")));
        assertEquals(503, error.getStatus());
    }

    @Test
    void mapsSlowUpstreamToGatewayTimeout() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        var provider = new ItunesRecommendationProvider(new ObjectMapper(), searchUrl(), "US", Duration.ofMillis(100));
        RecommendationException error = assertThrows(RecommendationException.class,
                () -> provider.recommend(command("노래")));
        assertEquals(504, error.getStatus());
    }

    private String searchUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
    }

    private RecommendationCommand command(String message) {
        return new RecommendationCommand(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), message);
    }
}
