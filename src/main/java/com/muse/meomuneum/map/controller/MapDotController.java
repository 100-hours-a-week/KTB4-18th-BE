package com.muse.meomuneum.map.controller;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.map.catalog.MapZoneCatalog;
import com.muse.meomuneum.map.repository.MapDotRepository;
import com.muse.meomuneum.map.repository.MapDotRepository.LatestMapDotRecord;
import com.muse.meomuneum.recommendation.resolver.CurrentUserResolver;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/map-dots")
public class MapDotController {
    private final MapZoneCatalog catalog;
    private final MapDotRepository repository;
    private final ObjectMapper objectMapper;
    private final CurrentUserResolver currentUserResolver;

    public MapDotController(MapZoneCatalog catalog, MapDotRepository repository, ObjectMapper objectMapper,
            CurrentUserResolver currentUserResolver) {
        this.catalog = catalog;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<MapDotsResponse>> getMapDots(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            Authentication authentication) {
        Long userId = currentUserResolver.resolve(authentication);
        List<LatestMapDotRecord> records = userId == null ? List.of() : repository.findLatestRecordsByUser(userId);
        Map<Long, LatestMapDotRecord> latestRecords = records.stream()
                .collect(Collectors.toMap(LatestMapDotRecord::mapDotId, Function.identity()));
        List<MapDotResponse> items = catalog.mapDots().stream()
                .map(dot -> MapDotResponse.from(dot, latestRecords.get(dot.mapDotId())))
                .toList();
        ApiResponse<MapDotsResponse> response = new ApiResponse<>("map dots retrieved", new MapDotsResponse(items));
        String etag = etag(response);
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(CacheControl.noCache().cachePrivate())
                    .varyBy(HttpHeaders.AUTHORIZATION).eTag(etag).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePrivate())
                .varyBy(HttpHeaders.AUTHORIZATION).eTag(etag).body(response);
    }

    private boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        return Arrays.stream(ifNoneMatch.split(","))
                .map(String::trim)
                .anyMatch(value -> value.equals("*") || value.equals(etag)
                        || (value.startsWith("W/") && value.substring(2).equals(etag)));
    }

    private String etag(ApiResponse<MapDotsResponse> response) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(response));
            return '"' + HexFormat.of().formatHex(digest) + '"';
        } catch (NoSuchAlgorithmException | JacksonException exception) {
            throw new IllegalStateException("지도 응답의 ETag를 생성할 수 없습니다.", exception);
        }
    }

    public record MapDotsResponse(List<MapDotResponse> items) {
    }

    /** API 명세의 snake_case 응답 필드를 보존하는 경계 DTO. */
    public record MapDotResponse(long map_dot_id, String code, String album_cover_url, String latest_recorded_at) {
        private static MapDotResponse from(MapZoneCatalog.MapDot mapDot, LatestMapDotRecord latestRecord) {
            return new MapDotResponse(mapDot.mapDotId(), mapDot.code(),
                    latestRecord == null ? null : latestRecord.albumCoverUrl(),
                    latestRecord == null ? null : latestRecord.recordedAt().toString());
        }
    }
}
