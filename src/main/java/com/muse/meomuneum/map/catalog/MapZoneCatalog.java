package com.muse.meomuneum.map.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class MapZoneCatalog {
    private static final int EXPECTED_ZONE_COUNT = 1050;
    private static final String CATALOG_RESOURCE_PATH = "map-zones.json";

    private final MapZoneCatalogDocument catalog;

    public MapZoneCatalog(ObjectMapper objectMapper) {
        this.catalog = load(objectMapper);
        validate(catalog);
    }

    public String version() {
        return catalog.version();
    }

    public List<MapZone> zones() {
        return catalog.zones();
    }

    /**
     * 지도 응답에서 사용하는 도트 식별자 목록이다. DB의 map_dots 테이블을 추가하지 않는
     * 현재 범위에서는 좌표 카탈로그의 고정 순서를 식별자로 사용한다.
     */
    public List<MapDot> mapDots() {
        return IntStream.range(0, catalog.zones().size())
                .mapToObj(index -> new MapDot(index + 1L, catalog.zones().get(index).code()))
                .toList();
    }

    public Optional<MapZone> findContaining(double latitude, double longitude) {
        return catalog.zones().stream()
                .filter(zone -> contains(zone, latitude, longitude))
                .findFirst();
    }

    private MapZoneCatalogDocument load(ObjectMapper objectMapper) {
        try (InputStream inputStream = new ClassPathResource(CATALOG_RESOURCE_PATH).getInputStream()) {
            return objectMapper.readValue(inputStream, MapZoneCatalogDocument.class);
        } catch (IOException exception) {
            throw new IllegalStateException("지도 구역 카탈로그를 읽을 수 없습니다.", exception);
        }
    }

    private void validate(MapZoneCatalogDocument document) {
        if (document.version() == null || document.version().isBlank()) {
            throw new IllegalStateException("지도 구역 카탈로그 version이 비어 있습니다.");
        }
        if (document.zones() == null || document.zones().size() != EXPECTED_ZONE_COUNT) {
            throw new IllegalStateException("지도 구역 카탈로그의 구역 수가 올바르지 않습니다.");
        }

        Set<String> codes = new HashSet<>();
        Set<String> gridPositions = new HashSet<>();
        for (MapZone zone : document.zones()) {
            validateZone(zone, codes, gridPositions);
        }
    }

    private void validateZone(MapZone zone, Set<String> codes, Set<String> gridPositions) {
        if (zone.code() == null || zone.code().isBlank() || !codes.add(zone.code())) {
            throw new IllegalStateException("지도 구역 코드가 비어 있거나 중복되었습니다.");
        }
        if (zone.gridRow() < 0 || zone.gridColumn() < 0
                || !gridPositions.add(zone.gridRow() + ":" + zone.gridColumn())) {
            throw new IllegalStateException("지도 구역 격자 위치가 올바르지 않습니다.");
        }
        if (!isValidCoordinate(zone.nw()) || !isValidCoordinate(zone.se()) || !isValidCoordinate(zone.center())) {
            throw new IllegalStateException("지도 구역 좌표가 올바르지 않습니다.");
        }
        if (zone.nw().latitude() <= zone.se().latitude()
                || zone.nw().longitude() >= zone.se().longitude()
                || !contains(zone, zone.center().latitude(), zone.center().longitude())) {
            throw new IllegalStateException("지도 구역 경계 또는 중심 좌표가 올바르지 않습니다.");
        }
    }

    private boolean isValidCoordinate(Coordinate coordinate) {
        return coordinate != null
                && Double.isFinite(coordinate.latitude())
                && Double.isFinite(coordinate.longitude())
                && coordinate.latitude() >= -90
                && coordinate.latitude() <= 90
                && coordinate.longitude() >= -180
                && coordinate.longitude() <= 180;
    }

    private boolean contains(MapZone zone, double latitude, double longitude) {
        return zone.nw().latitude() >= latitude
                && latitude > zone.se().latitude()
                && zone.nw().longitude() <= longitude
                && longitude < zone.se().longitude();
    }

    public record MapZoneCatalogDocument(String version, List<MapZone> zones) {
        public MapZoneCatalogDocument {
            zones = zones == null ? List.of() : List.copyOf(zones);
        }
    }

    public record MapZone(
            String code,
            int gridRow,
            int gridColumn,
            List<String> regions,
            Coordinate center,
            Coordinate nw,
            Coordinate se
    ) {
        public MapZone {
            regions = regions == null ? List.of() : List.copyOf(regions);
        }
    }

    public record Coordinate(double latitude, double longitude) {}

    public record MapDot(long mapDotId, String code) {}
}
