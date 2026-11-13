package com.muse.meomuneum.chat.music;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Official metadata APIs only. No media URL extraction, downloading or audio proxying. */
public final class YouTubeMusicVideoProvider implements ChatMusicVideoProvider {
    private final HttpClient http;
    private final URI endpoint;
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey;
    private final ChatMusicVideoBudget budget;
    private final Semaphore permits;
    private final int searchDaily;
    private final int detailsDaily;

    public YouTubeMusicVideoProvider(String apiKey, ChatMusicVideoBudget budget, int concurrency, int searchDaily,
            int detailsDaily) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
                .build(),
                URI.create("https://www.googleapis.com/youtube/v3/"), apiKey, budget, concurrency, searchDaily,
                detailsDaily);
    }

    YouTubeMusicVideoProvider(HttpClient http, URI endpoint, String apiKey, ChatMusicVideoBudget budget,
            int concurrency, int searchDaily, int detailsDaily) {
        if (apiKey == null || !apiKey.matches("[A-Za-z0-9_-]{10,256}") || concurrency < 1 || concurrency > 16
                || searchDaily < 1
                || detailsDaily < 1) {
            throw new IllegalArgumentException("YouTube metadata configuration is required");
        }
        this.apiKey = apiKey;
        this.http = http;
        this.endpoint = endpoint;
        this.budget = budget;
        this.permits = new Semaphore(concurrency);
        this.searchDaily = searchDaily;
        this.detailsDaily = detailsDaily;
    }

    @Override
    public List<Video> search(String title, String artist) {
        return search(title, artist, System.nanoTime() + 8_000_000_000L);
    }

    @Override
    public List<Video> search(String title, String artist, long deadlineNanos) {
        var result = request("search", "part=snippet&type=video&maxResults=50&regionCode=KR"
                + "&videoEmbeddable=true&videoSyndicated=true&fields=" + encode("items(id/videoId)")
                + "&q=" + encode(title + " " + artist), deadlineNanos);
        var ids = new LinkedHashSet<String>();
        for (var item : result.path("items")) {
            String id = item.path("id").path("videoId").asText("");
            if (id.matches("[A-Za-z0-9_-]{11}")) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return verifyAll(List.copyOf(ids), deadlineNanos);
    }

    @Override
    public List<Video> verifyAll(List<String> videoIds) {
        return verifyAll(videoIds, System.nanoTime() + 4_000_000_000L);
    }

    private List<Video> verifyAll(List<String> videoIds, long deadlineNanos) {
        if (videoIds == null || videoIds.size() > MAX_CANDIDATES || videoIds.stream()
                .anyMatch(id -> id == null || !id.matches("[A-Za-z0-9_-]{11}"))) {
            throw new IllegalArgumentException("Invalid video identifiers");
        }
        var ids = new LinkedHashSet<>(videoIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        var details = request("videos", "part=snippet,contentDetails,status&id=" + String.join(",", ids)
                + "&fields=" + encode("items(id,snippet(channelId,title,channelTitle,liveBroadcastContent),"
                        + "contentDetails(duration,regionRestriction,contentRating/ytRating),"
                        + "status(privacyStatus,uploadStatus,embeddable))"),
                deadlineNanos);
        List<Video> videos = new ArrayList<>();
        for (String id : ids) {
            for (var item : details.path("items")) {
                if (id.equals(item.path("id").asText())) {
                    var video = playable(item);
                    if (video != null) {
                        videos.add(video);
                    }
                    break;
                }
            }
        }
        return List.copyOf(videos);
    }

    @Override
    public Video verify(String videoId) {
        var videos = verifyAll(java.util.Collections.singletonList(videoId));
        return videos.isEmpty() ? null : videos.getFirst();
    }

    static Video playable(JsonNode item) {
        var snippet = item.path("snippet");
        var status = item.path("status");
        var details = item.path("contentDetails");
        String id = item.path("id").asText("");
        String channel = snippet.path("channelId").asText("");
        String title = snippet.path("title").asText("");
        String channelTitle = snippet.path("channelTitle").asText("");
        if (!id.matches("[A-Za-z0-9_-]{11}") || !channel.matches("[A-Za-z0-9_-]{1,64}")
                || title.isBlank() || title.length() > 255 || channelTitle.isBlank() || channelTitle.length() > 255
                || !"none".equals(snippet.path("liveBroadcastContent").asText())
                || !"public".equals(status.path("privacyStatus").asText())
                || !"processed".equals(status.path("uploadStatus").asText())
                || !status.path("embeddable").asBoolean(false)
                || "ytAgeRestricted".equals(details.path("contentRating").path("ytRating").asText())) {
            return null;
        }
        var restriction = details.path("regionRestriction");
        if ((restriction.has("allowed") && !containsKr(restriction.path("allowed")))
                || containsKr(restriction.path("blocked"))) {
            return null;
        }
        long duration;
        try {
            duration = Duration.parse(details.path("duration").asText("")).toMillis();
        } catch (RuntimeException invalid) {
            return null;
        }
        if (duration < 1 || duration > 9_999_999_999L) {
            return null;
        }
        // A fixed official thumbnail host avoids forwarding arbitrary URLs supplied as metadata.
        return new Video(id, channel, title, channelTitle, "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", duration);
    }

    private static boolean containsKr(JsonNode countries) {
        for (var country : countries) {
            if ("KR".equals(country.asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode request(String operation, String query, long deadlineNanos) {
        if (!permits.tryAcquire()) {
            throw new ChatMusicException(503, "VIDEO_PROVIDER_BUSY");
        }
        CompletableFuture<HttpResponse<byte[]>> call = null;
        try {
            budget.reserve("provider-minute", 60, 60);
            budget.reserve("provider-day-" + operation, 86400, operation.equals("search") ? searchDaily : detailsDaily);
            long remaining = Math.min(4_000_000_000L, deadlineNanos - System.nanoTime());
            if (remaining <= 0) {
                throw new ChatMusicException(503, "VIDEO_SEARCH_UNAVAILABLE");
            }
            var request = HttpRequest.newBuilder(URI.create(endpoint + operation
                    + "?" + query + "&key=" + encode(apiKey))).timeout(Duration.ofNanos(remaining)).GET().build();
            call = http.sendAsync(request, info -> new LimitedBody());
            var response = call.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) {
                throw new ChatMusicException(503, "VIDEO_SEARCH_UNAVAILABLE");
            }
            var body = json.readTree(response.body());
            if (!body.path("items").isArray() || body.path("items").size() > MAX_CANDIDATES || body.has("error")) {
                throw new ChatMusicException(503, "VIDEO_SEARCH_UNAVAILABLE");
            }
            return body;
        } catch (ChatMusicException problem) {
            throw problem;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ChatMusicException(503, "VIDEO_SEARCH_UNAVAILABLE");
        } catch (Exception unavailable) {
            // Never propagate a request URI or API key through errors/logging.
            throw new ChatMusicException(503, "VIDEO_SEARCH_UNAVAILABLE");
        } finally {
            if (call != null && !call.isDone()) {
                call.cancel(true);
            }
            permits.release();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 262144) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("Metadata response too large"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
