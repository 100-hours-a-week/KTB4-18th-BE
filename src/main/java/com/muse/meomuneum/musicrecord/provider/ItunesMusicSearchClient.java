package com.muse.meomuneum.musicrecord.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ItunesMusicSearchClient {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private final String url;
    private final String country;
    private final Duration timeout;

    public ItunesMusicSearchClient(ObjectMapper mapper,
            @Value("${music.itunes.search-url:https://itunes.apple.com/search}") String url,
            @Value("${music.itunes.country:US}") String country,
            @Value("${music.itunes.timeout:8s}") Duration timeout) {
        this.mapper = mapper;
        this.url = url;
        this.country = country;
        this.timeout = timeout;
    }
    public List<MusicItem> search(String query) {
        return request("search", "term=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&country=" + country + "&media=music&entity=song&limit=200");
    }

    public Optional<MusicItem> lookup(String trackId) {
        if (trackId == null || !trackId.matches("[0-9]+")) {
            return Optional.empty();
        }
        return request("lookup", "id=" + trackId + "&country=" + country + "&entity=song")
                .stream().filter(item -> trackId.equals(item.external_music_id())).findFirst();
    }

    private List<MusicItem> request(String operation, String parameters) {
        try {
            String endpoint = "lookup".equals(operation) ? url.replaceAll("/search$", "/lookup") : url;
            URI uri = URI.create(endpoint + "?" + parameters);
            var response = client.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw providerFailure(operation + "_http_" + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode results = root == null ? null : root.path("results");
            if (results == null || !results.isArray()) {
                throw providerFailure(operation + "_invalid_body");
            }
            var items = new ArrayList<MusicItem>();
            for (JsonNode result : results) {
                if (result.path("trackId").canConvertToLong() && !result.path("trackName").asText().isBlank()
                        && !result.path("artistName").asText().isBlank()) {
                    items.add(new MusicItem(null, "ITUNES", result.path("trackId").asText(),
                            result.path("trackName").asText(), result.path("artistName").asText(),
                            nullable(result, "artworkUrl100"), nullable(result, "previewUrl"), null, false));
                }
            }
            return items;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw providerFailure(operation + "_interrupted");
        } catch (IOException | tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            throw providerFailure(operation + "_transport_or_parse");
        }
    }
    private MusicRecordException providerFailure(String reason) {
        return new MusicRecordException(reason, org.springframework.http.HttpStatus.BAD_GATEWAY,
                "music provider unavailable");
    }
    private String nullable(JsonNode value, String field) {
        return value.path(field).isTextual() ? value.path(field).asText() : null;
    }
}
