package com.muse.meomuneum.chat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ChatRoomProvisioningMigrationTest {

    private static final String MIGRATION_PATH = "db/migration/V20260923162348__seed_chat_regions_and_rooms.sql";

    @Test
    void recordsTheOfficialSnapshotAndExpectedRegionCounts() throws IOException {
        String migration = readMigration();

        assertTrue(migration.contains("Snapshot effective date: 2026-09-17"));
        assertTrue(migration.contains("Active SIDO rows: 16, active SIGUNGU rows: 269"));
        assertTrue(migration
                .contains("Source ZIP SHA-256: 44b96f4a86ad102057463a05aae8842f1d706d3e9e69d2dfc409023bf75ca56b"));
        assertEquals(269, countOccurrences(migration, " AS `parent_code`"));
    }

    private int countOccurrences(String source, String target) {
        int count = 0;
        int start = 0;
        while ((start = source.indexOf(target, start)) >= 0) {
            count++;
            start += target.length();
        }
        return count;
    }

    private String readMigration() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(MIGRATION_PATH)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
