package com.muse.meomuneum.recommendation.provider;

import java.io.IOException;
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
public class AiRecommendationProvider implements RecommendationProvider {
    private static final int MAX_TRACK_COUNT = 5;

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final String authToken;
    private final Duration readTimeout;

    public AiRecommendationProvider(ObjectMapper mapper,
            @Value("${recommendation.ai.base-url}") String baseUrl,
            @Value("${recommendation.ai.auth-token:}") String authToken,
            @Value("${recommendation.ai.connect-timeout:3s}") Duration connectTimeout,
            @Value("${recommendation.ai.read-timeout:10s}") Duration readTimeout) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/chat/messages");
        this.authToken = authToken;
        this.readTimeout = readTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public List<TrackData> recommend(RecommendationCommand command) {
        String body = requestBody(command);
        var requestBuilder = HttpRequest.newBuilder(endpoint).timeout(readTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!authToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + authToken);
        }

        try {
            var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 503) {
                throw new RecommendationException(503, "AI 추천 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
            }
            if (response.statusCode() != 200) {
                throw new RecommendationException(502, "AI 추천 서비스의 응답을 확인할 수 없습니다.");
            }
            return parseTracks(response.body());
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

    private List<TrackData> parseTracks(String body) throws JacksonException {
        JsonNode tracksNode = mapper.readTree(body).path("tracks");
        if (!tracksNode.isArray() || tracksNode.size() > MAX_TRACK_COUNT) {
            throw new RecommendationException(502, "AI 추천 서비스의 응답을 확인할 수 없습니다.");
        }

        var seenIds = new HashSet<String>();
        var tracks = new LinkedHashMap<String, TrackData>();
        for (JsonNode item : tracksNode) {
            String externalId = requiredText(item, "track_id");
            String title = requiredText(item, "title");
            String artist = requiredText(item, "artist");
            if (!externalId.chars().allMatch(Character::isDigit)) {
                throw new RecommendationException(502, "AI 추천 서비스의 응답을 확인할 수 없습니다.");
            }
            if (!seenIds.add(externalId)) {
                continue;
            }
            String songKey = artist.trim().toLowerCase(Locale.ROOT) + ":" + title.trim().toLowerCase(Locale.ROOT);
            tracks.putIfAbsent(songKey, new TrackData("ITUNES", externalId, title, artist,
                    optionalText(item, "artwork_url"), optionalText(item, "preview_url")));
        }
        return List.copyOf(tracks.values());
    }

    private String requiredText(JsonNode item, String field) {
        String value = optionalText(item, field);
        if (value == null) {
            throw new RecommendationException(502, "AI 추천 서비스의 응답을 확인할 수 없습니다.");
        }
        return value;
    }

    private String optionalText(JsonNode item, String field) {
        JsonNode value = item.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private record AiRecommendationRequest(String thread_id, String request_id, String message) {
    }
}
