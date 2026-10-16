package com.muse.meomuneum.recommendation.provider;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.exception.RecommendationException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "recommendation.image", name = "provider", havingValue = "ai")
public class AiImageRecommendationProvider implements ImageStreamingRecommendationProvider {
    private static final String BOUNDARY = "MuseImage" + UUID.randomUUID().toString().replace("-", "");
    private static final long MAX_RESPONSE_BYTES = 2L * 1024 * 1024;
    private static final ScheduledExecutorService DEADLINE_EXECUTOR = Executors
            .newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "recommendation-image-deadline");
                thread.setDaemon(true);
                return thread;
            });

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final Duration deadline;

    public AiImageRecommendationProvider(ObjectMapper mapper,
            @Value("${recommendation.ai.base-url}") String baseUrl,
            @Value("${recommendation.ai.connect-timeout:3s}") Duration connectTimeout,
            @Value("${recommendation.image.timeout:60s}") Duration deadline) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/images");
        this.deadline = deadline;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public List<TrackData> recommend(ImageRecommendationCommand command, ImageRecommendationStreamListener listener) {
        long startedAt = System.nanoTime();
        String boundary = BOUNDARY + UUID.randomUUID().toString().replace("-", "");
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(deadline)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "text/event-stream")
                .POST(multipartBody(command, boundary)).build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream body = response.body();
            if (response.statusCode() != 200) {
                body.close();
                throw new RecommendationException(response.statusCode() == 504 ? 504 : 502,
                        "AI 추천 서비스의 응답을 확인할 수 없습니다.");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
                body.close();
                throw invalidResponse();
            }
            long remaining = deadline.toNanos() - (System.nanoTime() - startedAt);
            if (remaining <= 0) {
                body.close();
                throw timeout();
            }
            AtomicBoolean expired = new AtomicBoolean();
            var deadlineTask = DEADLINE_EXECUTOR.schedule(() -> {
                expired.set(true);
                try {
                    body.close();
                } catch (IOException ignored) {
                    // Closing the body interrupts a blocked SSE read at the deadline.
                }
            }, remaining, TimeUnit.NANOSECONDS);
            try (body) {
                return readEvents(new LimitedInputStream(body, MAX_RESPONSE_BYTES), listener, expired);
            } finally {
                deadlineTask.cancel(false);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RecommendationException(503, "AI 추천 요청이 중단됐습니다.");
        } catch (ResponseTooLargeException exception) {
            throw invalidResponse();
        } catch (IOException exception) {
            if (System.nanoTime() - startedAt >= deadline.toNanos()) {
                throw timeout();
            }
            throw new RecommendationException(503, "AI 추천 서비스에 연결할 수 없습니다.");
        } catch (JacksonException exception) {
            throw invalidResponse();
        }
    }

    private HttpRequest.BodyPublisher multipartBody(ImageRecommendationCommand command, String boundary) {
        var publishers = new ArrayList<HttpRequest.BodyPublisher>();
        publishers.add(bytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; "
                + "filename=\"image.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"));
        publishers.add(HttpRequest.BodyPublishers.ofByteArray(command.image()));
        publishers.add(bytes("\r\n"));
        addPart(publishers, boundary, "thread_id", command.threadId().toString());
        addPart(publishers, boundary, "request_id", command.requestId().toString());
        publishers.add(bytes("--" + boundary + "--\r\n"));
        return HttpRequest.BodyPublishers.concat(publishers.toArray(HttpRequest.BodyPublisher[]::new));
    }

    private void addPart(List<HttpRequest.BodyPublisher> publishers, String boundary, String name, String value) {
        publishers.add(bytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + value + "\r\n"));
    }

    private HttpRequest.BodyPublisher bytes(String value) {
        return HttpRequest.BodyPublishers.ofByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    private List<TrackData> readEvents(InputStream input, ImageRecommendationStreamListener listener,
            AtomicBoolean expired) throws IOException {
        String eventName = "";
        StringBuilder data = new StringBuilder();
        Progress progress = new Progress();
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (expired.get()) {
                    throw timeout();
                }
                if (line.isEmpty()) {
                    dispatch(eventName, data.toString(), progress, listener);
                    eventName = "";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    String value = line.substring(5);
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
        } catch (IOException exception) {
            if (expired.get()) {
                throw timeout();
            }
            throw exception;
        }
        if (progress.done && eventName.isBlank() && data.isEmpty()) {
            return progress.tracks;
        }
        throw invalidResponse();
    }

    private void dispatch(String eventName, String data, Progress progress,
            ImageRecommendationStreamListener listener) throws JacksonException {
        if (progress.done && !eventName.isBlank()) {
            throw invalidResponse();
        }
        if (eventName.isBlank()) {
            if (!data.isBlank()) {
                throw invalidResponse();
            }
            return;
        }
        if ("status".equals(eventName) || "scene".equals(eventName)) {
            if ("status".equals(eventName)) {
                String status = mapper.readTree(data).path("status").asString();
                if (progress.verdict == null && "analyzing".equals(status) && !progress.analyzingSeen) {
                    progress.analyzingSeen = true;
                    return;
                }
                if (Boolean.TRUE.equals(progress.verdict) && "searching".equals(status)
                        && progress.sceneSeen && progress.textSeen && !progress.searchingSeen && !progress.tracksSeen) {
                    progress.searchingSeen = true;
                    return;
                }
                throw invalidResponse();
            }
            if (progress.verdict == null || progress.sceneSeen || progress.rejectedSeen || progress.tracksSeen) {
                throw invalidResponse();
            }
            progress.sceneSeen = true;
            return;
        }
        if ("verdict".equals(eventName)) {
            if (progress.verdict != null || progress.tracksSeen || data.isBlank() || !progress.analyzingSeen) {
                throw invalidResponse();
            }
            JsonNode node = mapper.readTree(data).path("usable");
            if (!node.isBoolean()) {
                throw invalidResponse();
            }
            progress.verdict = node.booleanValue();
            listener.onVerdict(progress.verdict);
            return;
        }
        if ("text".equals(eventName)) {
            if (!Boolean.TRUE.equals(progress.verdict) || !progress.sceneSeen || progress.rejectedSeen
                    || (progress.tracksSeen && !progress.tracks.isEmpty())) {
                throw invalidResponse();
            }
            JsonNode delta = mapper.readTree(data).path("delta");
            if (!delta.isTextual()) {
                throw invalidResponse();
            }
            if (!delta.asText().isEmpty()) {
                listener.onText(delta.asText());
                progress.textSeen = true;
            }
            return;
        }
        if ("rejected".equals(eventName)) {
            if (!Boolean.FALSE.equals(progress.verdict) || progress.rejectedSeen || progress.tracksSeen) {
                throw invalidResponse();
            }
            String reason = mapper.readTree(data).path("reason").asString();
            if (!List.of("scene_unreadable", "not_a_place", "provider_blocked").contains(reason)) {
                throw invalidResponse();
            }
            if (!"provider_blocked".equals(reason) && !progress.sceneSeen) {
                throw invalidResponse();
            }
            progress.rejectedSeen = true;
            listener.onRejected(reason);
            return;
        }
        if ("tracks".equals(eventName)) {
            if (progress.verdict == null || progress.tracksSeen
                    || (Boolean.FALSE.equals(progress.verdict) && !progress.rejectedSeen)) {
                throw invalidResponse();
            }
            if (Boolean.TRUE.equals(progress.verdict) && (!progress.sceneSeen || !progress.searchingSeen)) {
                throw invalidResponse();
            }
            JsonNode array = mapper.readTree(data).path("tracks");
            List<TrackData> tracks = parseTracks(array);
            if (Boolean.FALSE.equals(progress.verdict) && !tracks.isEmpty()) {
                throw invalidResponse();
            }
            progress.tracksSeen = true;
            progress.tracks = tracks;
            listener.onTracks(tracks);
            return;
        }
        if ("error".equals(eventName)) {
            throw new RecommendationException(502, "AI 추천 생성 중 오류가 발생했습니다.");
        }
        if ("done".equals(eventName)) {
            if (progress.verdict == null || (progress.verdict && !progress.tracksSeen)
                    || (!progress.verdict && !progress.rejectedSeen)) {
                throw invalidResponse();
            }
            if (!progress.verdict && !progress.tracksSeen) {
                progress.tracks = List.of();
                progress.tracksSeen = true;
                listener.onTracks(progress.tracks);
            }
            progress.done = true;
            return;
        }
        throw invalidResponse();
    }

    private List<TrackData> parseTracks(JsonNode node) {
        if (!node.isArray() || node.size() > 5) {
            throw invalidResponse();
        }
        HashSet<String> ids = new HashSet<>();
        var result = new java.util.ArrayList<TrackData>();
        for (JsonNode item : node) {
            String id = text(item, "track_id");
            String title = text(item, "title");
            String artist = text(item, "artist");
            if (!id.chars().allMatch(Character::isDigit) || !ids.add(id)) {
                throw invalidResponse();
            }
            result.add(new TrackData("ITUNES", id, title, artist, optional(item, "artwork_url"),
                    optional(item, "preview_url"), optional(item, "store_url"), null));
        }
        return List.copyOf(result);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalidResponse();
        }
        return value.asText().trim();
    }

    private String optional(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private RecommendationException timeout() {
        return new RecommendationException(504, "AI 추천 시간이 초과됐습니다.");
    }

    private RecommendationException invalidResponse() {
        return new RecommendationException(502, "AI 추천 서비스의 응답을 확인할 수 없습니다.");
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maximumBytes;
        private long bytesRead;

        private LimitedInputStream(InputStream input, long maximumBytes) {
            super(input);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                increment(count);
            }
            return count;
        }

        private void increment(int count) throws ResponseTooLargeException {
            bytesRead += count;
            if (bytesRead > maximumBytes) {
                throw new ResponseTooLargeException();
            }
        }
    }

    private static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Progress {
        private Boolean verdict;
        private boolean rejectedSeen;
        private boolean tracksSeen;
        private boolean done;
        private boolean analyzingSeen;
        private boolean sceneSeen;
        private boolean searchingSeen;
        private boolean textSeen;
        private List<TrackData> tracks;
    }
}
