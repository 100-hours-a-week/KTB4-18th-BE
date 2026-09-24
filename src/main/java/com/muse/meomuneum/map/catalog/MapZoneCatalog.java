package com.muse.meomuneum.map.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class MapZoneCatalog {
    private static final int EXPECTED_ZONE_COUNT = 1050;
    private static final String CATALOG_RESOURCE_PATH = "map-zones.json";
    private static final Pattern MAP_DOT_CODE_PATTERN = Pattern.compile("^KR-COAST-(\\d{4})$");

    private final MapZoneCatalogDocument catalog;

    @Autowired
    public MapZoneCatalog(ObjectMapper objectMapper) {
        this(load(objectMapper));
    }

    MapZoneCatalog(MapZoneCatalogDocument catalog) {
        this.catalog = catalog;
        validate(this.catalog);
    }

    public String version() {
        return catalog.version();
    }

    public List<MapZone> zones() {
        return catalog.zones();
    }

    /** 지도 코드의 불변 숫자 suffix를 도트 식별자로 사용한다. */
    public List<MapDot> mapDots() {
        return catalog.zones().stream()
                .map(zone -> new MapDot(mapDotIdFromCode(zone.code()), zone.code()))
                .toList();
    }

    public Optional<MapZone> findContaining(double latitude, double longitude) {
        return catalog.zones().stream()
                .filter(zone -> contains(zone, latitude, longitude))
                .findFirst();
    }

    private static MapZoneCatalogDocument load(ObjectMapper objectMapper) {
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
        Set<Long> mapDotIds = new HashSet<>();
        for (MapZone zone : document.zones()) {
            validateZone(zone, codes, gridPositions, mapDotIds);
        }
    }

    private void validateZone(MapZone zone, Set<String> codes, Set<String> gridPositions, Set<Long> mapDotIds) {
        if (zone.code() == null || zone.code().isBlank() || !codes.add(zone.code())) {
            throw new IllegalStateException("지도 구역 코드가 비어 있거나 중복되었습니다.");
        }
        if (!mapDotIds.add(mapDotIdFromCode(zone.code()))) {
            throw new IllegalStateException("지도 도트 ID가 중복되었습니다.");
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

    private long mapDotIdFromCode(String code) {
        Matcher matcher = MAP_DOT_CODE_PATTERN.matcher(code);
        if (!matcher.matches()) {
            throw new IllegalStateException("지도 구역 코드 형식이 올바르지 않습니다.");
        }

        long mapDotId = Long.parseLong(matcher.group(1));
        if (mapDotId < 1 || mapDotId > EXPECTED_ZONE_COUNT) {
            throw new IllegalStateException("지도 도트 ID 범위가 올바르지 않습니다.");
        }
        return mapDotId;
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
