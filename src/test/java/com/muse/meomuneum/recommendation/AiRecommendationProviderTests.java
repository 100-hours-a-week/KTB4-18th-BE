package com.muse.meomuneum.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.provider.AiRecommendationProvider;
import com.muse.meomuneum.recommendation.provider.RecommendationCommand;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class AiRecommendationProviderTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsContractRequestAndMapsDistinctPartialTracks() throws IOException {
        UUID threadId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        server = server(exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(requestBody.contains("\"thread_id\":\"" + threadId + "\""));
            assertTrue(requestBody.contains("\"request_id\":\"" + requestId + "\""));
            assertTrue(requestBody.contains("\"message\":\"비 오는 밤\""));
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"message":"추천 결과입니다.","tracks":[
                      {"title":"첫 곡","artist":"가수","track_id":"101","preview_url":null,
                       "artwork_url":"https://example.test/1.jpg","store_url":"https://example.test/1","reason":"분위기"},
                      {"title":"첫 곡","artist":"가수","track_id":"102","preview_url":null,
                       "artwork_url":"https://example.test/2.jpg","store_url":"https://example.test/2","reason":"중복"},
                      {"title":"둘째 곡","artist":"다른 가수","track_id":"103","preview_url":"https://example.test/p",
                       "artwork_url":null,"store_url":"https://example.test/3","reason":"분위기"}
                    ]}
                    """);
        });

        var provider = provider(Duration.ofSeconds(2));
        var tracks = provider.recommend(new RecommendationCommand(threadId, requestId, "비 오는 밤"));

        assertEquals(2, tracks.size());
        assertEquals("101", tracks.get(0).externalId());
        assertEquals("103", tracks.get(1).externalId());
        assertEquals("ITUNES", tracks.get(0).provider());
    }

    @Test
    void returnsEmptyTracksForZeroResult() throws IOException {
        server = server(exchange -> respond(exchange, 200, "{\"message\":\"다른 조건을 입력해 주세요.\",\"tracks\":[]}"));

        var tracks = provider(Duration.ofSeconds(2)).recommend(command());

        assertTrue(tracks.isEmpty());
    }

    @Test
    void mapsServiceUnavailableAndTimeout() throws IOException {
        server = server(exchange -> respond(exchange, 503,
                "{\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"reason\":\"MODEL_UNAVAILABLE\"}}"));
        RecommendationException unavailable = assertThrows(RecommendationException.class,
                () -> provider(Duration.ofSeconds(2)).recommend(command()));
        assertEquals(503, unavailable.getStatus());
        server.stop(0);

        server = server(exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        RecommendationException timeout = assertThrows(RecommendationException.class,
                () -> provider(Duration.ofMillis(100)).recommend(command()));
        assertEquals(504, timeout.getStatus());
    }

    @Test
    void rejectsMalformedTrackResponse() throws IOException {
        server = server(exchange -> respond(exchange, 200,
                "{\"message\":\"추천\",\"tracks\":[{\"title\":\"곡\",\"artist\":\"가수\",\"track_id\":\"not-number\"}]}"));

        RecommendationException exception = assertThrows(RecommendationException.class,
                () -> provider(Duration.ofSeconds(2)).recommend(command()));

        assertEquals(502, exception.getStatus());
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/chat/messages", handler);
        httpServer.start();
        return httpServer;
    }

    private AiRecommendationProvider provider(Duration timeout) {
        return new AiRecommendationProvider(new ObjectMapper(), baseUrl(), "test-token", Duration.ofSeconds(1),
                timeout);
    }

    private RecommendationCommand command() {
        return new RecommendationCommand(UUID.randomUUID(), UUID.randomUUID(), "노래 추천");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
