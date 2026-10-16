package com.muse.meomuneum.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.provider.AiImageRecommendationProvider;
import com.muse.meomuneum.recommendation.provider.ImageRecommendationCommand;
import com.muse.meomuneum.recommendation.provider.ImageRecommendationStreamListener;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class AiImageRecommendationProviderTests {
    @Test
    void sendsMultipartAndParsesUsableImageStream() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(requestBody, "event: status\ndata: {\"status\":\"analyzing\"}\n\n"
                + "event: verdict\ndata: {\"usable\":true}\n\n"
                + "event: scene\ndata: {\"scene\":\"park\"}\n\n"
                + "event: text\ndata: {\"delta\":\"근처 산책길 느낌이에요.\"}\n\n"
                + "event: status\ndata: {\"status\":\"searching\"}\n\n"
                + "event: tracks\ndata: {\"tracks\":[]}\n\n"
                + "event: text\ndata: {\"delta\":\"곡 정보를 찾지 못했어요.\"}\n\n"
                + "event: done\ndata: {}\n\n");
        try {
            var provider = provider(server);
            List<Boolean> verdicts = new ArrayList<>();
            List<List<TrackData>> trackEvents = new ArrayList<>();
            var tracks = provider.recommend(command(), listener(verdicts, trackEvents));

            assertThat(tracks).isEmpty();
            assertThat(verdicts).containsExactly(true);
            assertThat(trackEvents).containsExactly(List.of());
            assertThat(requestBody.get()).contains("name=\"image\"", "Content-Type: image/jpeg",
                    "name=\"thread_id\"", "11111111-1111-1111-1111-111111111111",
                    "name=\"request_id\"", "22222222-2222-2222-2222-222222222222");
            assertThat(requestBody.get()).doesNotContain("name=\"message\"", "user_context", "base64");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stripsUpstreamImageTrackReasonAndIgnoresHostileRejectionCopy() throws Exception {
        String usable = """
                event: status
                data: {"status":"analyzing"}

                event: verdict
                data: {"usable":true}

                event: scene
                data: {"scene":"park"}

                event: text
                data: {"delta":"A safe public sentence."}

                event: status
                data: {"status":"searching"}

                event: tracks
                data: {"tracks":[{"track_id":"1","title":"Song","artist":"Artist",
                data: "reason":"PRIVATE TRACK DIAGNOSTIC"}]}

                event: done
                data: {}

                """;
        HttpServer server = server(new AtomicReference<>(), usable);
        try {
            List<List<TrackData>> trackEvents = new ArrayList<>();
            var tracks = provider(server).recommend(command(), listener(new ArrayList<>(), trackEvents));
            assertThat(tracks).hasSize(1);
            assertThat(tracks.getFirst().reason()).isNull();
            assertThat(trackEvents.getFirst().getFirst().reason()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesRejectedEventWithoutForwardingItsFreeTextMessage() throws Exception {
        String rejected = "event: status\ndata: {\"status\":\"analyzing\"}\n\n"
                + "event: verdict\ndata: {\"usable\":false}\n\n"
                + "event: scene\ndata: {\"scene\":\"unclear\"}\n\n"
                + "event: rejected\ndata: {\"reason\":\"scene_unreadable\","
                + "\"message\":\"PRIVATE UPSTREAM MESSAGE\"}\n\n"
                + "event: done\ndata: {}\n\n";
        HttpServer server = server(new AtomicReference<>(), rejected);
        try {
            List<String> reasons = new ArrayList<>();
            var listener = new ImageRecommendationStreamListener() {
                @Override
                public void onVerdict(boolean usable) {
                }
                @Override
                public void onRejected(String reason) {
                    reasons.add(reason);
                }
                @Override
                public void onText(String delta) {
                    throw new AssertionError("rejection cannot publish text");
                }
                @Override
                public void onTracks(List<TrackData> tracks) {
                    assertThat(tracks).isEmpty();
                }
            };
            assertThat(provider(server).recommend(command(), listener)).isEmpty();
            assertThat(reasons).containsExactly("scene_unreadable");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsTracksBeforeVerdictAsProviderFailure() throws Exception {
        HttpServer server = server(new AtomicReference<>(), "event: tracks\ndata: {\"tracks\":[]}\n\n");
        try {
            assertThatThrownBy(
                    () -> provider(server).recommend(command(), listener(new ArrayList<>(), new ArrayList<>())))
                    .isInstanceOf(RecommendationException.class)
                    .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(502);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closesSlowSseBodyAtTheImageDeadline() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/images", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("event: status\ndata: {\"status\":\"analyzing\"}\n\n".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            var timedProvider = new AiImageRecommendationProvider(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2),
                    Duration.ofMillis(150));
            assertThatThrownBy(() -> timedProvider.recommend(command(), listener(new ArrayList<>(), new ArrayList<>())))
                    .isInstanceOf(RecommendationException.class)
                    .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(504);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(AtomicReference<String> requestBody, String response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/images", exchange -> {
            requestBody
                    .set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private AiImageRecommendationProvider provider(HttpServer server) {
        return new AiImageRecommendationProvider(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    private ImageRecommendationCommand command() {
        return new ImageRecommendationCommand(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"),
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
    }

    private ImageRecommendationStreamListener listener(List<Boolean> verdicts, List<List<TrackData>> tracks) {
        return new ImageRecommendationStreamListener() {
            @Override
            public void onVerdict(boolean usable) {
                verdicts.add(usable);
            }

            @Override
            public void onRejected(String reason) {
            }

            @Override
            public void onText(String delta) {
            }

            @Override
            public void onTracks(List<TrackData> values) {
                tracks.add(values);
            }
        };
    }
}
