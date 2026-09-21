package com.muse.meomuneum.recommendation.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.exception.RecommendationException;

/** AI 팀 연동 전에는 대화의 입력 문장을 iTunes에서 직접 검색합니다. */
@Component
public class ItunesRecommendationProvider implements RecommendationProvider {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String searchUrl;
    private final String country;
    private final Duration timeout;

    public ItunesRecommendationProvider(ObjectMapper mapper,
                                        @Value("${recommendation.itunes.search-url:https://itunes.apple.com/search}")
                                        String searchUrl,
                                        @Value("${recommendation.itunes.country:US}") String country,
                                        @Value("${recommendation.itunes.timeout:8s}") Duration timeout) {
        this.mapper = mapper;
        this.searchUrl = searchUrl;
        this.country = country;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public List<TrackData> recommend(List<String> prompts) {
        String query = String.join(" ", prompts).trim();
        if (query.isEmpty()) {
            return List.of();
        }
        // iTunes는 GET 검색을 제공하므로 긴 연속 대화에서는 최신 조건을 우선합니다.
        if (query.length() > 250) {
            query = query.substring(query.length() - 250).trim();
        }
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create(searchUrl + "?term=" + encoded + "&country=" + country
                + "&media=music&entity=song&limit=25");
        var request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RecommendationException(503, "음악 검색 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
            }
            JsonNode results = mapper.readTree(response.body()).path("results");
            if (!results.isArray()) {
                throw new RecommendationException(503, "음악 검색 결과를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
            }
            var seenIds = new HashSet<String>();
            var tracks = new LinkedHashMap<String, TrackData>();
            for (JsonNode item : results) {
                if (!"song".equals(item.path("kind").asText()) || !item.path("trackId").canConvertToLong()) {
                    continue;
                }
                String id = item.path("trackId").asText();
                String title = optionalText(item, "trackName");
                String artist = optionalText(item, "artistName");
                if (title == null || artist == null) {
                    continue;
                }
                if (!seenIds.add(id)) {
                    continue;
                }
                String songKey = artist.trim().toLowerCase(Locale.ROOT) + ":" + title.trim().toLowerCase(Locale.ROOT);
                tracks.putIfAbsent(songKey, new TrackData("ITUNES", id, title, artist,
                        optionalText(item, "artworkUrl100"), optionalText(item, "previewUrl")));
                if (tracks.size() == 5) {
                    break;
                }
            }
            return List.copyOf(tracks.values());
        } catch (HttpTimeoutException exception) {
            throw new RecommendationException(504, "음악 검색 시간이 초과됐습니다. 다시 시도해 주세요.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RecommendationException(503, "음악 검색이 중단됐습니다. 다시 시도해 주세요.");
        } catch (JacksonException exception) {
            throw new RecommendationException(503, "음악 검색 결과를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        } catch (IOException exception) {
            throw new RecommendationException(503, "음악 검색 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private String optionalText(JsonNode item, String field) {
        JsonNode value = item.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
