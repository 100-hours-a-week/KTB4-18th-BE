package com.muse.meomuneum.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.muse.meomuneum.recommendation.dto.SpeechAudio;
import com.muse.meomuneum.recommendation.exception.SpeechTranscriptionException;
import com.muse.meomuneum.recommendation.provider.AiSpeechToTextProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiSpeechToTextProviderTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsPureBase64AndMimeTypeAndReturnsTrimmedTranscript() throws IOException {
        byte[] audioBytes = {1, 2, 3, 4};
        server = server(exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            String encoded = request.path("audio_base64").asText();
            assertEquals(Base64.getEncoder().encodeToString(audioBytes), encoded);
            assertFalse(encoded.startsWith("data:"));
            assertEquals("audio/webm", request.path("mime_type").asText());
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"transcript\":\"  비 오는 날의 노래  \"}");
        });

        String transcript = provider(Duration.ofSeconds(2))
                .transcribe(new SpeechAudio(audioBytes, "audio/webm", "voice.webm", 10));

        assertEquals("비 오는 날의 노래", transcript);
    }

    @Test
    void mapsAiValidationErrorsToPublicStatuses() throws IOException {
        server = server(exchange -> respond(exchange, 400,
                "{\"code\":\"INVALID_REQUEST\",\"details\":{\"reason\":\"AUDIO_TOO_LONG\"}}"));
        assertStatus(400);
        server.stop(0);

        server = server(exchange -> respond(exchange, 413,
                "{\"code\":\"PAYLOAD_TOO_LARGE\",\"details\":{\"reason\":\"AUDIO_TOO_LARGE\"}}"));
        assertStatus(413);
        server.stop(0);

        server = server(exchange -> respond(exchange, 415,
                "{\"code\":\"UNSUPPORTED_MEDIA_TYPE\",\"details\":{\"reason\":\"MIME_TYPE_MISMATCH\"}}"));
        assertStatus(400);
        server.stop(0);

        server = server(exchange -> respond(exchange, 503,
                "{\"code\":\"SERVICE_UNAVAILABLE\",\"details\":{\"reason\":\"TRANSCRIPTION_SERVICE_UNAVAILABLE\"}}"));
        assertStatus(502);
    }

    @Test
    void rejectsBlankOrMalformedTranscript() throws IOException {
        server = server(exchange -> respond(exchange, 200, "{\"transcript\":\"   \"}"));
        assertStatus(502);
        server.stop(0);

        server = server(exchange -> respond(exchange, 200, "not-json"));
        assertStatus(502);
    }

    @Test
    void mapsReadTimeoutToGatewayTimeout() throws IOException {
        server = server(exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        SpeechTranscriptionException exception = assertThrows(SpeechTranscriptionException.class,
                () -> provider(Duration.ofMillis(100)).transcribe(audio()));

        assertEquals(504, exception.getStatus());
    }

    private void assertStatus(int expectedStatus) {
        SpeechTranscriptionException exception = assertThrows(SpeechTranscriptionException.class,
                () -> provider(Duration.ofSeconds(2)).transcribe(audio()));
        assertEquals(expectedStatus, exception.getStatus());
        assertTrue(exception.getMessage().length() > 5);
    }

    private SpeechAudio audio() {
        return new SpeechAudio(new byte[]{1, 2, 3}, "audio/webm", "voice.webm", 10);
    }

    private HttpServer server(HttpHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/transcriptions", handler);
        httpServer.start();
        return httpServer;
    }

    private AiSpeechToTextProvider provider(Duration timeout) {
        return new AiSpeechToTextProvider(mapper, baseUrl(), "test-token", Duration.ofSeconds(1), timeout);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
