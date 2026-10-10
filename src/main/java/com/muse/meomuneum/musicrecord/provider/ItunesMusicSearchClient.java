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

import com.muse.meomuneum.music.domain.MusicMetadataPolicy;
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
            @Value("${recommendation.itunes.search-url:https://itunes.apple.com/search}") String url,
            @Value("${recommendation.itunes.country:US}") String country,
            @Value("${recommendation.itunes.timeout:8s}") Duration timeout) {
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
                if ("lookup".equals(operation) && result.path("trackId").canConvertToLong()) {
                    MusicMetadataPolicy.validate(result.path("trackName").asText(), result.path("artistName").asText());
                }
                if (result.path("trackId").canConvertToLong() && !result.path("trackName").asText().isBlank()
                        && !result.path("artistName").asText().isBlank()) {
                    items.add(new MusicItem(null, "ITUNES", result.path("trackId").asText(),
                            result.path("trackName").asText(), result.path("artistName").asText(),
                            resizeArtwork(nullable(result, "artworkUrl100")), nullable(result, "previewUrl"), null,
                            false));
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
    private String resizeArtwork(String artworkUrl) {
        if (artworkUrl == null) {
            return null;
        }
        try {
            URI uri = URI.create(artworkUrl);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                return artworkUrl;
            }
            String path = uri.getRawPath();
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (!"100x100bb.jpg".equals(filename) && !filename.matches(".+\\.100x100-75\\.jpg")) {
                return artworkUrl;
            }
            int pathEnd = artworkUrl.length();
            if (uri.getRawFragment() != null) {
                pathEnd -= uri.getRawFragment().length() + 1;
            }
            if (uri.getRawQuery() != null) {
                pathEnd -= uri.getRawQuery().length() + 1;
            }
            int sizeStart = pathEnd - filename.length();
            if (!"100x100bb.jpg".equals(filename)) {
                sizeStart += filename.length() - "100x100-75.jpg".length();
            }
            return artworkUrl.substring(0, sizeStart) + "680x680" + artworkUrl.substring(sizeStart + 7);
        } catch (IllegalArgumentException exception) {
            return artworkUrl;
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
