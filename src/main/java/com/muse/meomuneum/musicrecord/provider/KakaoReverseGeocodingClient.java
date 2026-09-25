package com.muse.meomuneum.musicrecord.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

@Component
public class KakaoReverseGeocodingClient {
    private static final URI ENDPOINT = URI.create("https://dapi.kakao.com/v2/local/geo/coord2regioncode.json");

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;

    @Autowired
    public KakaoReverseGeocodingClient(
            ObjectMapper mapper,
            @Value("${kakao.maps.rest-api-key:}") String apiKey) {
        this(mapper, HttpClient.newHttpClient(), ENDPOINT, apiKey);
    }

    KakaoReverseGeocodingClient(ObjectMapper mapper, HttpClient client, URI endpoint, String apiKey) {
        this.mapper = mapper;
        this.client = client;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    public String reverseGeocode(double latitude, double longitude) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new MusicRecordException("kakao_rest_api_key_missing");
        }
        try {
            URI uri = URI.create(endpoint + "?x=" + longitude + "&y=" + latitude + "&input_coord=WGS84");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "KakaoAK " + apiKey)
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new MusicRecordException("kakao_reverse_geocoding_http_" + response.statusCode());
            }
            return parsePlaceName(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MusicRecordException("kakao_reverse_geocoding_interrupted");
        } catch (HttpTimeoutException exception) {
            throw new MusicRecordException("kakao_reverse_geocoding_timeout");
        } catch (IOException exception) {
            throw new MusicRecordException("kakao_reverse_geocoding_transport_failed");
        } catch (JacksonException exception) {
            throw new MusicRecordException("kakao_reverse_geocoding_invalid_json");
        } catch (IllegalArgumentException exception) {
            throw new MusicRecordException("kakao_reverse_geocoding_invalid_request");
        }
    }

    private String parsePlaceName(String body) throws JacksonException {
        if (body == null || body.isBlank()) {
            throw new MusicRecordException("kakao_reverse_geocoding_empty_body");
        }
        JsonNode root = mapper.readTree(body);
        if (root == null || !root.isObject()) {
            throw new MusicRecordException("kakao_reverse_geocoding_invalid_body");
        }
        JsonNode documents = root.path("documents");
        if (!documents.isArray() || documents.isEmpty()) {
            throw new MusicRecordException("kakao_reverse_geocoding_location_not_found");
        }
        JsonNode administrative = null;
        JsonNode legal = null;
        for (JsonNode document : documents) {
            if ("B".equals(document.path("region_type").asText()) && legal == null) {
                legal = document;
            } else if ("H".equals(document.path("region_type").asText()) && administrative == null) {
                administrative = document;
            }
        }
        JsonNode selected = legal != null ? legal : administrative;
        if (selected == null) {
            throw new MusicRecordException("kakao_reverse_geocoding_region_not_found");
        }
        JsonNode sidoNode = selected.path("region_1depth_name");
        JsonNode sigunguNode = selected.path("region_2depth_name");
        if (!sidoNode.isTextual() || !sigunguNode.isTextual()) {
            throw new MusicRecordException("kakao_reverse_geocoding_region_incomplete");
        }
        String sido = sidoNode.asText().trim();
        String sigungu = sigunguNode.asText().trim();
        if (sido.isBlank() || sigungu.isBlank()) {
            throw new MusicRecordException("kakao_reverse_geocoding_region_incomplete");
        }
        return normalizeSido(sido) + " " + sigungu;
    }

    private String normalizeSido(String value) {
        for (String suffix : new String[] {"특별자치시", "특별자치도", "특별시", "광역시"}) {
            if (value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }
}
