package com.muse.meomuneum.locationrecommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.muse.meomuneum.locationrecommendation.config.LocationRecommendationSettings;
import com.muse.meomuneum.locationrecommendation.provider.AiLocationRecommendationProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class AiLocationRecommendationProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsOnlyLatitudeAndLongitudeAndParsesNormalizedTracks() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestId = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        server = server(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            respond(exchange, 200, """
                    {"tracks":[{"track_id":"42","title":"Song","artist":"Artist",
                    "preview_url":null,"artwork_url":"https://example.test/art.jpg",
                    "store_url":"https://example.test/store"}]}
                    """);
        });
        var tracks = provider().recommend(37.5744, 127.0395, Duration.ofSeconds(2), () -> true)
                .result().get(2, TimeUnit.SECONDS);

        assertThat(requestBody.get()).contains("\"latitude\":37.5744", "\"longitude\":127.0395")
                .doesNotContain("requestId", "userId", "threadId", "accuracyMeters");
        assertThat(requestId.get()).isNull();
        assertThat(contentType.get()).isEqualTo("application/json");
        assertThat(tracks).containsExactly(
                new com.muse.meomuneum.locationrecommendation.dto.LocationRecommendationDtos.TrackResponse("42", "Song",
                        "Artist", null,
                        "https://example.test/art.jpg", "https://example.test/store"));
    }

    @Test
    void doesNotSendWhenDispatchGuardRejectsTheAttempt() throws Exception {
        AtomicInteger received = new AtomicInteger();
        server = server(exchange -> {
            received.incrementAndGet();
            respond(exchange, 200, "{\"tracks\":[]}");
        });

        var call = provider().recommend(37.5, 127.0, Duration.ofSeconds(2), () -> false);

        assertThat(call.dispatched()).isFalse();
        assertThat(call.result().get(1, TimeUnit.SECONDS)).isEmpty();
        assertThat(received.get()).isZero();
    }

    @Test
    void emptyTracksAreValidAndMoreThanFiveTracksAreRejected() throws Exception {
        server = server(exchange -> respond(exchange, 200, "{\"tracks\":[]}"));
        assertThat(provider().recommend(37.5, 127.0, Duration.ofSeconds(2), () -> true)
                .result().get(2, TimeUnit.SECONDS)).isEmpty();
        server.stop(0);
        server = server(exchange -> respond(exchange, 200,
                "{\"tracks\":[" + validTrack() + "," + validTrack() + "," + validTrack() + "," + validTrack()
                        + "," + validTrack() + "," + validTrack() + "]}"));

        assertThatThrownBy(() -> provider().recommend(37.5, 127.0, Duration.ofSeconds(2), () -> true).result().get(2,
                TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AiLocationRecommendationProvider.AiProviderException.class);
    }

    @Test
    void typedLocationContextFailureIsNormalizedAndTimeoutCancelsTheExchange() throws Exception {
        for (String reason : java.util.List.of("LOCATION_CONTEXT_UNAVAILABLE", "MODEL_UNAVAILABLE",
                "MODEL_RESPONSE_INVALID", "MUSIC_CATALOG_UNAVAILABLE", "LOCATION_TIMEOUT")) {
            server = server(exchange -> respond(exchange, 503,
                    "{\"details\":{\"reason\":\"" + reason + "\"},"
                            + "\"message\":\"private detail\"}"));
            assertThatThrownBy(() -> provider().recommend(37.5, 127.0, Duration.ofSeconds(2), () -> true)
                    .result().get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(exception -> assertThat(exception.getCause())
                            .isInstanceOfSatisfying(AiLocationRecommendationProvider.AiProviderException.class,
                                    cause -> assertThat(cause.reason()).isEqualTo(reason)))
                    .hasMessageNotContaining("private detail");
            server.stop(0);
        }
        server = server(exchange -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"tracks\":[]}");
        });
        var call = provider().recommend(37.5, 127.0, Duration.ofMillis(30), () -> true);

        assertThatThrownBy(() -> call.result().get(1, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        call.cancel().run();
    }

    @Test
    void normalizesReasonsOutsideTheLocationApiContract() throws Exception {
        server = server(exchange -> respond(exchange, 503,
                "{\"details\":{\"reason\":\"RATE_LIMITED\"}}"));

        assertThatThrownBy(() -> provider().recommend(37.5, 127.0, Duration.ofSeconds(2), () -> true)
                .result().get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(exception -> assertThat(exception.getCause())
                        .isInstanceOfSatisfying(AiLocationRecommendationProvider.AiProviderException.class,
                                cause -> assertThat(cause.reason()).isEqualTo("UPSTREAM_UNAVAILABLE")))
                .hasMessageNotContaining("RATE_LIMITED");
    }

    @Test
    void mapsAi422AndRejectsUnsafeTrackUrls() throws Exception {
        server = server(exchange -> respond(exchange, 422, "{\"reason\":\"INVALID_REQUEST\"}"));
        assertThatThrownBy(() -> provider().recommend(37.5, 127.0, Duration.ofSeconds(2), () -> true)
                .result().get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AiLocationRecommendationProvider.AiProviderException.class)
                .hasMessageNotContaining("INVALID_REQUEST");
        server.stop(0);
        for (String url : java.util.List.of("http://example.test/audio", "https://user@example.test/audio",
                "https://example.test:65536/audio")) {
            server = server(exchange -> respond(exchange, 200,
                    "{\"tracks\":[{\"track_id\":\"42\",\"title\":\"Song\",\"artist\":\"Artist\","
                            + "\"preview_url\":\"" + url + "\",\"artwork_url\":null,"
                            + "\"store_url\":null}]}"));

            assertThatThrownBy(() -> provider().recommend(37.5, 127.0, Duration.ofSeconds(2), () -> true)
                    .result().get(2, TimeUnit.SECONDS))
                    .as("rejects unsafe URL %s", url)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(AiLocationRecommendationProvider.AiProviderException.class);
            server.stop(0);
        }
    }

    @Test
    void rejectsNonStringRequiredTrackFields() throws Exception {
        for (String track : java.util.List.of(
                "{\"track_id\":42,\"title\":\"Song\",\"artist\":\"Artist\"}",
                "{\"track_id\":\"42\",\"title\":12,\"artist\":\"Artist\"}",
                "{\"track_id\":\"42\",\"title\":\"Song\",\"artist\":1}")) {
            server = server(exchange -> respond(exchange, 200, "{\"tracks\":[" + track + "]}"));

            assertThatThrownBy(() -> provider().recommend(37.5, 127.0, Duration.ofSeconds(2), () -> true)
                    .result().get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(AiLocationRecommendationProvider.AiProviderException.class);

            server.stop(0);
        }
    }

    @Test
    void rejectsMissingOrAdditionalCommonTrackFields() throws Exception {
        for (String track : java.util.List.of(
                "{\"track_id\":\"42\",\"title\":\"Song\",\"artist\":\"Artist\"}",
                "{\"track_id\":\"42\",\"title\":\"Song\",\"artist\":\"Artist\","
                        + "\"preview_url\":null,\"artwork_url\":null,\"store_url\":null,\"extra\":true}")) {
            server = server(exchange -> respond(exchange, 200, "{\"tracks\":[" + track + "]}"));

            assertThatThrownBy(() -> provider().recommend(37.5, 127.0, Duration.ofSeconds(2), () -> true)
                    .result().get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(AiLocationRecommendationProvider.AiProviderException.class);

            server.stop(0);
        }
    }

    @Test
    void oversizedSuccessfulAiResponseIsTypedAsInvalidResponse() throws Exception {
        server = server(exchange -> respond(exchange, 200, "{\"tracks\":[]}" + " ".repeat(262_144)));

        assertThatThrownBy(() -> provider().recommend(37.5, 127.0, Duration.ofSeconds(2), () -> true)
                .result().get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(exception -> assertThat(exception.getCause())
                        .isInstanceOfSatisfying(
                                AiLocationRecommendationProvider.ResponseBodyTooLargeException.class,
                                cause -> assertThat(cause.statusCode()).isEqualTo(200)));
    }

    private AiLocationRecommendationProvider provider() {
        return new AiLocationRecommendationProvider(new ObjectMapper(), HttpClient.newHttpClient(),
                new LocationRecommendationSettings(java.net.URI.create("http://127.0.0.1:" + server.getAddress()
                        .getPort() + "/v1/location/recommendations"), Duration.ofSeconds(2),
                        Duration.ofSeconds(1), Duration.ofSeconds(130), true));
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/v1/location/recommendations", handler);
        value.start();
        return value;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String validTrack() {
        return "{\"track_id\":\"42\",\"title\":\"Song\",\"artist\":\"Artist\","
                + "\"preview_url\":null,\"artwork_url\":null,\"store_url\":null}";
    }
}
