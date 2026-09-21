package com.muse.meomuneum.map.catalog;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapZoneCatalogTests {
    private final MapZoneCatalog catalog = new MapZoneCatalog(new ObjectMapper());

    @Test
    void loadsEveryReportZoneWithUniqueGridPosition() {
        assertEquals("2026-09-07", catalog.version());
        assertEquals(1050, catalog.zones().size());
        assertEquals(
                1050,
                catalog.zones().stream().map(zone -> zone.gridRow() + ":" + zone.gridColumn()).distinct().count()
        );
    }

    @Test
    void derivesStableMapDotIdsFromTheCatalogOrder() {
        assertEquals(1050, catalog.mapDots().size());
        assertEquals(1, catalog.mapDots().getFirst().mapDotId());
        assertEquals("KR-COAST-5X5-0001", catalog.mapDots().getFirst().code());
    }

    @Test
    void findsTheContainingZoneUsingNorthWestAndSouthEastCorners() {
        assertEquals(
                "KR-COAST-5X5-0001",
                catalog.findContaining(38.4684909, 128.3049316).orElseThrow().code()
        );
        assertFalse(catalog.findContaining(0, 0).isPresent());

        MapZoneCatalog.MapZone firstZone = catalog.zones().getFirst();
        assertTrue(catalog.findContaining(firstZone.nw().latitude(), firstZone.nw().longitude()).isPresent());
        assertTrue(catalog.findContaining(firstZone.se().latitude(), firstZone.se().longitude()).isPresent());
        assertFalse(
                catalog.findContaining(firstZone.se().latitude(), firstZone.se().longitude())
                        .orElseThrow()
                        .code()
                        .equals(firstZone.code())
        );
    }
}
