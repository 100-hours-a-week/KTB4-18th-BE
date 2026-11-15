package com.muse.meomuneum.musicrecord.provider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.muse.meomuneum.music.domain.MusicMetadataPolicy;
import com.muse.meomuneum.music.exception.MusicMetadataException;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.StorefrontNames;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ItunesMusicSearchClient {
    private static final Duration LOCALIZATION_TIMEOUT = Duration.ofSeconds(2);

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();
    private final String url;
    private final String country;
    private final Duration timeout;

    public ItunesMusicSearchClient(ObjectMapper mapper,
            @Value("${recommendation.itunes.search-url:https://itunes.apple.com/search}") String url,
            @Value("${music.search.country:US}") String country,
            @Value("${recommendation.itunes.timeout:8s}") Duration timeout) {
        this.mapper = mapper;
        this.url = url;
        this.country = country;
        this.timeout = timeout;
    }
    public String country() {
        return country;
    }

    public List<MusicItem> search(String query) {
        List<MusicItem> items = request("search", "term=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&country=" + country + "&media=music&entity=song&limit=200");
        return items.isEmpty() ? items : localize(items);
    }

    public Optional<MusicItem> lookup(String trackId) {
        if (!com.muse.meomuneum.musicrecord.dto.SongRegistrationDtos.validTrackId(trackId)) {
            return Optional.empty();
        }
        return request("lookup", "id=" + trackId + "&country=" + country + "&entity=song")
                .stream().filter(item -> trackId.equals(item.external_music_id())).findFirst()
                .map(item -> localize(List.of(item)).getFirst());
    }

    public MusicItem retryKoreanLookup(MusicItem item) {
        return localize(List.of(item)).getFirst();
    }

    private List<MusicItem> localize(List<MusicItem> items) {
        Optional<Map<String, KoreanNames>> korean = lookupKoreanNames(items);
        return items.stream().map(item -> {
            KoreanNames match = korean.map(names -> names.get(item.external_music_id())).orElse(null);
            String titleKo = match == null ? null : match.title();
            String artistNameKo = match == null ? null : match.artistName();
            boolean primaryIsUs = "US".equalsIgnoreCase(country);
            var names = new StorefrontNames(primaryIsUs ? item.title() : null,
                    primaryIsUs ? item.artist_name() : null, titleKo, artistNameKo, korean.isEmpty());
            return new MusicItem(item.music_id(), item.provider(), item.external_music_id(),
                    titleKo == null ? item.title() : titleKo,
                    artistNameKo == null ? item.artist_name() : artistNameKo, item.album_cover_url(),
                    item.preview_url(), item.youtube_video_id(), item.is_queueable(), names, item.releaseEpochSecond());
        }).toList();
    }

    private Optional<Map<String, KoreanNames>> lookupKoreanNames(List<MusicItem> items) {
        String ids = items.stream().map(MusicItem::external_music_id).collect(Collectors.joining(","));
        Duration budget = timeout.compareTo(LOCALIZATION_TIMEOUT) < 0 ? timeout : LOCALIZATION_TIMEOUT;
        try {
            HttpRequest request = buildRequest("lookup", "id=" + ids + "&country=KR&lang=ko_kr&entity=song&limit=200",
                    budget);
            var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                return Optional.of(parseKoreanNames(response.get(budget.toNanos(), TimeUnit.NANOSECONDS)));
            } catch (InterruptedException | TimeoutException exception) {
                response.cancel(true);
                throw exception;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException | JacksonException | IllegalArgumentException
                | MusicRecordException exception) {
            return Optional.empty();
        }
    }

    private Map<String, KoreanNames> parseKoreanNames(HttpResponse<String> response) {
        var names = new HashMap<String, KoreanNames>();
        for (JsonNode result : results("lookup", response)) {
            JsonNode id = result.path("trackId");
            if ("song".equalsIgnoreCase(result.path("kind").asText())
                    && id.isIntegralNumber() && id.canConvertToLong() && id.asLong() > 0) {
                names.merge(id.asText(), new KoreanNames(validName(result, "trackName", 255),
                        validName(result, "artistName", MusicMetadataPolicy.MAX_ARTIST_NAME_LENGTH)),
                        KoreanNames::withMissingFieldsFrom);
            }
        }
        return names;
    }

    private String validName(JsonNode result, String field, int maxLength) {
        String value = nullable(result, field);
        return value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength ? null : value;
    }

    private record KoreanNames(String title, String artistName) {
        KoreanNames withMissingFieldsFrom(KoreanNames other) {
            return new KoreanNames(title == null ? other.title : title,
                    artistName == null ? other.artistName : artistName);
        }
    }

    private List<MusicItem> request(String operation, String parameters) {
        try {
            var response = client.send(buildRequest(operation, parameters, timeout),
                    HttpResponse.BodyHandlers.ofString());
            return parseResponse(operation, response);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw providerFailure(operation + "_interrupted");
        } catch (IOException | JacksonException | IllegalArgumentException exception) {
            throw providerFailure(operation + "_transport_or_parse");
        }
    }

    private HttpRequest buildRequest(String operation, String parameters, Duration requestTimeout) {
        String endpoint = "lookup".equals(operation) ? url.replaceAll("/search$", "/lookup") : url;
        URI uri = URI.create(endpoint + "?" + parameters);
        return HttpRequest.newBuilder(uri).timeout(requestTimeout).GET().build();
    }

    private JsonNode results(String operation, HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw providerFailure(operation + "_http_" + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode results = root == null ? null : root.path("results");
        if (results == null || !results.isArray()) {
            throw providerFailure(operation + "_invalid_body");
        }
        return results;
    }

    private List<MusicItem> parseResponse(String operation, HttpResponse<String> response) {
        var items = new ArrayList<MusicItem>();
        var seen = new java.util.HashSet<String>();
        for (JsonNode result : results(operation, response)) {
            if (!"song".equalsIgnoreCase(result.path("kind").asText())) {
                continue;
            }
            JsonNode id = result.path("trackId");
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0
                    || !result.path("trackName").isTextual() || !result.path("artistName").isTextual()) {
                throw providerFailure(operation + "_invalid_song");
            }
            String title = result.path("trackName").asText();
            String artist = result.path("artistName").asText();
            MusicMetadataPolicy.validate(title, artist);
            String artwork = resizeArtwork(nullable(result, "artworkUrl100"));
            String preview = nullable(result, "previewUrl");
            if ((artwork != null && artwork.codePointCount(0, artwork.length()) > 1000)
                    || (preview != null && preview.codePointCount(0, preview.length()) > 1000)) {
                throw new MusicMetadataException("music_url_too_long");
            }
            if (seen.add(id.asText()) && items.size() < 200) {
                items.add(new MusicItem(null, "ITUNES", id.asText(), title, artist, artwork, preview, null, false,
                        StorefrontNames.EMPTY, releaseEpochSecond(result)));
            }
        }
        return List.copyOf(items);
    }

    private Long releaseEpochSecond(JsonNode result) {
        String releaseDate = nullable(result, "releaseDate");
        if (releaseDate == null) {
            return null;
        }
        try {
            return Instant.parse(releaseDate).getEpochSecond();
        } catch (DateTimeParseException exception) {
            return null;
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
