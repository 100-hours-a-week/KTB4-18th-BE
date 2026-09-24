package com.muse.meomuneum.map.controller;

import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.map.catalog.MapZoneCatalog;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map-dots")
public class MapDotController {
    private final MapZoneCatalog catalog;

    public MapDotController(MapZoneCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<MapDotsResponse>> getMapDots(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        String etag = quoted(catalog.version());
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        List<MapDotResponse> items = catalog.mapDots().stream().map(MapDotResponse::from).toList();
        return ResponseEntity.ok().eTag(etag).body(new ApiResponse<>("map dots retrieved", new MapDotsResponse(items)));
    }

    private boolean matches(String ifNoneMatch, String etag) {
        return ifNoneMatch != null
                && Arrays.stream(ifNoneMatch.split(","))
                        .map(String::trim)
                        .anyMatch(value -> value.equals("*") || value.equals(etag));
    }

    private String quoted(String value) {
        return '"' + value + '"';
    }

    public record MapDotsResponse(List<MapDotResponse> items) {}

    /** API 명세의 snake_case 응답 필드를 보존하는 경계 DTO. */
    public record MapDotResponse(
            long map_dot_id,
            String code,
            String album_cover_url,
            String latest_recorded_at
    ) {
        private static MapDotResponse from(MapZoneCatalog.MapDot mapDot) {
            return new MapDotResponse(mapDot.mapDotId(), mapDot.code(), null, null);
        }
    }
}
