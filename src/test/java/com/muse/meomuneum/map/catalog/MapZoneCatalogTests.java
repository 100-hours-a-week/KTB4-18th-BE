package com.muse.meomuneum.map.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class MapZoneCatalogTests {
    private final MapZoneCatalog catalog = new MapZoneCatalog(new ObjectMapper());

    @Test
    void loadsEveryReportZoneWithUniqueGridPosition() {
        assertEquals("2026-09-07", catalog.version());
        assertEquals(1050, catalog.zones().size());
        assertEquals(1050,
                catalog.zones().stream().map(zone -> zone.gridRow() + ":" + zone.gridColumn()).distinct().count());
    }

    @Test
    void derivesStableMapDotIdsFromTheCatalogCode() {
        assertEquals(1050, catalog.mapDots().size());
        assertEquals(1, catalog.mapDots().getFirst().mapDotId());
        assertEquals("KR-COAST-0001", catalog.mapDots().getFirst().code());
        assertEquals(104, catalog.mapDots().stream().filter(mapDot -> mapDot.code().equals("KR-COAST-0104")).findFirst()
                .orElseThrow().mapDotId());
    }

    @Test
    void keepsMapDotIdsWhenTheCatalogOrderChanges() {
        List<MapZoneCatalog.MapZone> reorderedZones = new ArrayList<>(catalog.zones());
        Collections.reverse(reorderedZones);

        MapZoneCatalog reorderedCatalog = new MapZoneCatalog(
                new MapZoneCatalog.MapZoneCatalogDocument("reordered", reorderedZones));

        assertEquals(104, reorderedCatalog.mapDots().stream().filter(mapDot -> mapDot.code().equals("KR-COAST-0104"))
                .findFirst().orElseThrow().mapDotId());
    }

    @Test
    void rejectsMapDotCodesOutsideTheEstablishedConvention() {
        List<MapZoneCatalog.MapZone> zones = new ArrayList<>(catalog.zones());
        MapZoneCatalog.MapZone firstZone = zones.getFirst();
        zones.set(0, new MapZoneCatalog.MapZone("KR-COAST-00001", firstZone.gridRow(), firstZone.gridColumn(),
                firstZone.regions(), firstZone.center(), firstZone.nw(), firstZone.se()));

        assertThrows(IllegalStateException.class,
                () -> new MapZoneCatalog(new MapZoneCatalog.MapZoneCatalogDocument("invalid", zones)));
    }

    @Test
    void findsTheContainingZoneUsingNorthWestAndSouthEastCorners() {
        assertEquals("KR-COAST-0001", catalog.findContaining(38.4684909, 128.3049316).orElseThrow().code());
        assertFalse(catalog.findContaining(0, 0).isPresent());

        MapZoneCatalog.MapZone firstZone = catalog.zones().getFirst();
        assertTrue(catalog.findContaining(firstZone.nw().latitude(), firstZone.nw().longitude()).isPresent());
        assertTrue(catalog.findContaining(firstZone.se().latitude(), firstZone.se().longitude()).isPresent());
        assertFalse(catalog.findContaining(firstZone.se().latitude(), firstZone.se().longitude()).orElseThrow().code()
                .equals(firstZone.code()));
    }
}
