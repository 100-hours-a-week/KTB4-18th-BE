package com.muse.meomuneum.music.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Metadata checks cannot certify the song/channel relationship; the operator reviews that separately. */
public final class YouTubeVideoVerifier {
    private static final int MAX_BODY_BYTES = 65536;
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public YouTubeVideoVerifier(String apiKey) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://www.googleapis.com/youtube/v3/videos"), apiKey);
    }

    public YouTubeVideoVerifier(HttpClient client, URI endpoint, String apiKey) {
        if (apiKey == null || !apiKey.matches("[A-Za-z0-9_-]{10,256}")) {
            throw new IllegalArgumentException("YouTube API credential required");
        }
        this.client = client;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    public Video verify(String videoId, String expectedChannel) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new IllegalArgumentException("invalid video identifier");
        }
        URI uri = URI.create(endpoint + "?part=snippet,contentDetails,status&id=" + videoId + "&key=" + apiKey);
        try {
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("YouTube metadata unavailable");
                }
                byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) {
                    throw new IllegalStateException("YouTube metadata too large");
                }
                return parse(mapper.readTree(bytes), videoId, expectedChannel);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("YouTube verification interrupted");
        } catch (IOException | tools.jackson.core.JacksonException exception) {
            // Do not retain an exception whose URI may contain the API key.
            throw new IllegalStateException("YouTube metadata unavailable");
        }
    }

    public static Video parse(JsonNode root, String videoId, String expectedChannel) {
        if (root == null || !root.path("items").isArray() || root.path("items").size() != 1) {
            throw new IllegalArgumentException("video missing or inaccessible");
        }
        JsonNode item = root.path("items").get(0);
        JsonNode details = item.path("contentDetails");
        JsonNode snippet = item.path("snippet");
        JsonNode status = item.path("status");
        if (!videoId.equals(item.path("id").asText())
                || expectedChannel == null || !expectedChannel.equals(snippet.path("channelId").asText())
                || !"none".equals(snippet.path("liveBroadcastContent").asText())
                || !status.path("embeddable").isBoolean() || !status.path("embeddable").asBoolean()
                || !"public".equals(status.path("privacyStatus").asText())
                || !"processed".equals(status.path("uploadStatus").asText())
                || "ytAgeRestricted".equals(details.path("contentRating").path("ytRating").asText())) {
            throw new IllegalArgumentException("video is not an approved playable source");
        }
        JsonNode restriction = details.path("regionRestriction");
        for (String kind : new String[]{"allowed", "blocked"}) {
            if (restriction.has(kind) && !restriction.path(kind).isArray()) {
                throw new IllegalArgumentException("invalid region restrictions");
            }
        }
        if ((restriction.has("allowed") && !contains(restriction.path("allowed"), "KR"))
                || contains(restriction.path("blocked"), "KR")) {
            throw new IllegalArgumentException("video unavailable in playback country");
        }
        long duration;
        try {
            duration = Duration.parse(details.path("duration").asText()).toMillis();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid video duration");
        }
        if (duration <= 0) {
            throw new IllegalArgumentException("invalid video duration");
        }
        return new Video(videoId, expectedChannel, duration);
    }

    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    public record Video(String id, String channelId, long durationMillis) {
    }
}
