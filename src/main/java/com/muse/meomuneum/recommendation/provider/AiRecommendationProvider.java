package com.muse.meomuneum.recommendation.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.exception.RecommendationException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "recommendation", name = "provider", havingValue = "ai")
public class AiRecommendationProvider implements StreamingRecommendationProvider {
    private static final int MAX_TRACK_COUNT = 5;

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final Duration readTimeout;

    public AiRecommendationProvider(ObjectMapper mapper,
            @Value("${recommendation.ai.base-url}") String baseUrl,
            @Value("${recommendation.ai.connect-timeout:3s}") Duration connectTimeout,
            @Value("${recommendation.ai.read-timeout:10s}") Duration readTimeout) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/messages");
        this.readTimeout = readTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public List<TrackData> recommend(RecommendationCommand command) {
        return recommend(command, new RecommendationStreamListener() {
            @Override
            public void onText(String delta) {
                // 기존 동기 호출자는 스트림 문장을 소비하지 않습니다.
            }

            @Override
            public void onTracks(List<TrackData> tracks) {
                // 기존 동기 호출자는 최종 결과만 사용합니다.
            }
        });
    }

    @Override
    public List<TrackData> recommend(RecommendationCommand command, RecommendationStreamListener listener) {
        String body = requestBody(command);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(readTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();

        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 503) {
                response.body().close();
                throw new RecommendationException(503, "AI 추천 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
            }
            if (response.statusCode() != 200) {
                response.body().close();
                throw new RecommendationException(502, "AI 추천 서비스의 응답을 확인할 수 없습니다.");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream")) {
                response.body().close();
                throw new RecommendationException(502, "AI 추천 서비스의 응답 형식이 올바르지 않습니다.");
            }
            return readEvents(response.body(), listener);
        } catch (HttpTimeoutException exception) {
            throw new RecommendationException(504, "AI 추천 시간이 초과됐습니다. 다시 시도해 주세요.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RecommendationException(503, "AI 추천 요청이 중단됐습니다. 다시 시도해 주세요.");
        } catch (JacksonException exception) {
            throw new RecommendationException(502, "AI 추천 서비스의 응답을 확인할 수 없습니다.");
        } catch (IOException exception) {
            throw new RecommendationException(503, "AI 추천 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    @Override
    public String providerName() {
        return "ai";
    }

    private String requestBody(RecommendationCommand command) {
        try {
            return mapper.writeValueAsString(new AiRecommendationRequest(command.threadId().toString(),
                    command.requestId().toString(), command.message()));
        } catch (JacksonException exception) {
            throw new RecommendationException(500, "AI 추천 요청을 생성할 수 없습니다.");
        }
    }

    private List<TrackData> readEvents(InputStream input, RecommendationStreamListener listener)
            throws IOException {
        String eventName = "";
        var data = new StringBuilder();
        List<TrackData> tracks = null;
        boolean done = false;
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    var parsed = dispatchEvent(eventName, data.toString(), tracks, done, listener);
                    eventName = "";
                    data.setLength(0);
                    tracks = parsed.tracks();
                    done = parsed.done();
                    if (done) {
                        return tracks;
                    }
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    String value = line.substring(5);
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
        }
        throw new RecommendationException(502, "AI 추천 스트림이 완료 전에 종료됐습니다.");
    }

    private EventProgress dispatchEvent(String eventName, String data, List<TrackData> currentTracks,
            boolean done, RecommendationStreamListener listener) throws JacksonException {
        if (eventName.isBlank()) {
            return new EventProgress(currentTracks, done);
        }
        if ("text".equals(eventName)) {
            String delta = parseTextDelta(data);
            if (!delta.isEmpty()) {
                listener.onText(delta);
            }
            return new EventProgress(currentTracks, done);
        }
        if ("tracks".equals(eventName)) {
            if (currentTracks != null || data.isBlank()) {
                throw invalidResponse();
            }
            JsonNode payload = mapper.readTree(data);
            JsonNode tracks = payload.isArray() ? payload : payload.path("tracks");
            List<TrackData> parsedTracks = parseTracks(tracks);
            listener.onTracks(parsedTracks);
            return new EventProgress(parsedTracks, done);
        }
        if ("error".equals(eventName)) {
            throw new RecommendationException(503, "AI 추천 생성 중 오류가 발생했습니다.");
        }
        if ("done".equals(eventName)) {
            if (currentTracks == null) {
                throw invalidResponse();
            }
            return new EventProgress(currentTracks, true);
        }
        throw invalidResponse();
    }

    private String parseTextDelta(String data) {
        if (data.isBlank()) {
            throw invalidResponse();
        }
        try {
            JsonNode payload = mapper.readTree(data);
            if (payload.isTextual()) {
                return payload.asText();
            }
            JsonNode delta = payload.path("delta");
            if (delta.isTextual()) {
                return delta.asText();
            }
            throw invalidResponse();
        } catch (JacksonException exception) {
            return data;
        }
    }

    private List<TrackData> parseTracks(JsonNode tracksNode) throws JacksonException {
        if (!tracksNode.isArray() || tracksNode.size() > MAX_TRACK_COUNT) {
            throw invalidResponse();
        }

        var seenIds = new HashSet<String>();
        var tracks = new LinkedHashMap<String, TrackData>();
        for (JsonNode item : tracksNode) {
            String externalId = requiredText(item, "track_id");
            String title = requiredText(item, "title");
            String artist = requiredText(item, "artist");
            if (!externalId.chars().allMatch(Character::isDigit)) {
                throw invalidResponse();
            }
            if (!seenIds.add(externalId)) {
                continue;
            }
            String songKey = artist.trim().toLowerCase(Locale.ROOT) + ":" + title.trim().toLowerCase(Locale.ROOT);
            tracks.putIfAbsent(songKey, new TrackData("ITUNES", externalId, title, artist,
                    optionalText(item, "artwork_url"), optionalText(item, "preview_url"),
                    optionalText(item, "store_url"), optionalText(item, "reason")));
        }
        return List.copyOf(tracks.values());
    }

    private String requiredText(JsonNode item, String field) {
        String value = optionalText(item, field);
        if (value == null) {
            throw invalidResponse();
        }
        return value;
    }

    private String optionalText(JsonNode item, String field) {
        JsonNode value = item.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private RecommendationException invalidResponse() {
        return new RecommendationException(502, "AI 추천 서비스의 응답을 확인할 수 없습니다.");
    }

    private record EventProgress(List<TrackData> tracks, boolean done) {
    }

    private record AiRecommendationRequest(String thread_id, String request_id, String message) {
    }
}
