package com.muse.meomuneum.location.provider;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.muse.meomuneum.location.config.ReverseGeocodingProperties;
import com.muse.meomuneum.location.exception.LocationErrorCode;
import com.muse.meomuneum.location.exception.LocationException;

@Component
public class KakaoRegionCoordinateResolver implements RegionCoordinateResolver {

    private static final String LEGAL_REGION_TYPE = "B";
    private static final int SIDO_CODE_LENGTH = 2;
    private static final int SIGUNGU_CODE_LENGTH = 5;

    private final RestClient restClient;
    private final ReverseGeocodingProperties properties;

    public KakaoRegionCoordinateResolver(
            @Qualifier("kakaoReverseGeocodingRestClient") RestClient restClient,
            ReverseGeocodingProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public ResolvedRegionCode resolve(double latitude, double longitude) {
        if (!StringUtils.hasText(properties.restApiKey())) {
            throw new LocationException(LocationErrorCode.REVERSE_GEOCODING_FAILED);
        }

        KakaoRegionResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/local/geo/coord2regioncode.json")
                            .queryParam("x", longitude)
                            .queryParam("y", latitude)
                            .queryParam("input_coord", "WGS84")
                            .build())
                    .header("Authorization", "KakaoAK " + properties.restApiKey())
                    .retrieve()
                    .body(KakaoRegionResponse.class);
        } catch (RestClientException exception) {
            throw new LocationException(LocationErrorCode.REVERSE_GEOCODING_FAILED);
        }

        return legalRegionCode(response);
    }

    private ResolvedRegionCode legalRegionCode(KakaoRegionResponse response) {
        if (response == null || response.documents() == null) {
            throw new LocationException(LocationErrorCode.REVERSE_GEOCODING_FAILED);
        }

        String legalCode = response.documents().stream()
                .filter(document -> LEGAL_REGION_TYPE.equals(document.regionType()))
                .map(KakaoRegionDocument::code)
                .filter(this::isValidLegalCode)
                .findFirst()
                .orElseThrow(() -> new LocationException(LocationErrorCode.REVERSE_GEOCODING_FAILED));

        return new ResolvedRegionCode(
                legalCode.substring(0, SIDO_CODE_LENGTH),
                legalCode.substring(0, SIGUNGU_CODE_LENGTH)
        );
    }

    private boolean isValidLegalCode(String code) {
        return code != null && code.length() >= SIGUNGU_CODE_LENGTH
                && code.chars().allMatch(Character::isDigit);
    }

    private record KakaoRegionResponse(List<KakaoRegionDocument> documents) {
    }

    private record KakaoRegionDocument(
            @JsonProperty("region_type") String regionType,
            String code) {
    }
}
