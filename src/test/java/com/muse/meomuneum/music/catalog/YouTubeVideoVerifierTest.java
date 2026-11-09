package com.muse.meomuneum.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

class YouTubeVideoVerifierTest {
    private static final String CHANNEL = "UC" + "a".repeat(22);
    private static final String BODY = """
            {"items":[{"id":"abcdefghijk","snippet":{"channelId":"%s","liveBroadcastContent":"none"},
            "status":{"embeddable":true,"privacyStatus":"public","uploadStatus":"processed"},
            "contentDetails":{"duration":"PT3M12S"}}]}
            """.formatted(CHANNEL);

    @Test
    void validatesMetadataAndSanitizesProviderFailures() throws Exception {
        var response = new AtomicReference<>(BODY);
        var status = new AtomicInteger(200);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/videos", exchange -> {
            assertThat(exchange.getRequestURI().getQuery()).contains("part=snippet,contentDetails,status");
            calls.incrementAndGet();
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var verifier = new YouTubeVideoVerifier(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/videos"), "fake-key-123456");
            assertThat(verifier.verify("abcdefghijk", CHANNEL).durationMillis()).isEqualTo(192000);
            status.set(403);
            response.set("fake-key-123456 upstream error details");
            assertThatThrownBy(() -> verifier.verify("abcdefghijk", CHANNEL))
                    .isInstanceOf(IllegalStateException.class).hasMessage("YouTube metadata unavailable")
                    .hasNoCause();
            status.set(200);
            response.set("x".repeat(65537));
            assertThatThrownBy(() -> verifier.verify("abcdefghijk", CHANNEL))
                    .hasMessage("YouTube metadata too large");
            assertThat(calls.get()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingInaccessibleRestrictedLiveWrongChannelAndInvalidDuration() {
        var mapper = new ObjectMapper();
        for (String body : List.of("{}", "{\"items\":[]}",
                BODY.replace("\"embeddable\":true", "\"embeddable\":false"),
                BODY.replace("\"privacyStatus\":\"public\"", "\"privacyStatus\":\"private\""),
                BODY.replace("\"liveBroadcastContent\":\"none\"", "\"liveBroadcastContent\":\"live\""),
                BODY.replace(CHANNEL, "UC" + "b".repeat(22)),
                BODY.replace("PT3M12S", "PT0S"), BODY.replace("PT3M12S", "invalid"),
                BODY.replace("\"duration\":", "\"regionRestriction\":{\"blocked\":[\"KR\"]},\"duration\":"),
                BODY.replace("\"duration\":", "\"regionRestriction\":{\"allowed\":[\"US\"]},\"duration\":"),
                BODY.replace("\"duration\":", "\"contentRating\":{\"ytRating\":\"ytAgeRestricted\"},\"duration\":"))) {
            assertThatThrownBy(() -> YouTubeVideoVerifier.parse(mapper.readTree(body), "abcdefghijk", CHANNEL))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void reviewRequiresEvidenceCanonicalIdentifiersAndBoundedValidity() {
        new MusicPlaybackSourceRepository.Review(1, "abcdefghijk", CHANNEL, "ARTIST", "review evidence",
                "operator", 30, 0).validate();
        assertThatThrownBy(() -> new MusicPlaybackSourceRepository.Review(1, "abcdefghijk", CHANNEL,
                "ARTIST", "", "operator", 30, 0).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MusicPlaybackSourceRepository.Review(1, "abcdefghijk", CHANNEL,
                "ARTIST", "review", "operator", 31, 0).validate()).isInstanceOf(IllegalArgumentException.class);
    }
}
