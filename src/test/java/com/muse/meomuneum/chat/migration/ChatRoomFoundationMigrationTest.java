package com.muse.meomuneum.chat.migration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ChatRoomFoundationMigrationTest {

    private static final String MIGRATION_PATH = "db/migration/V20260923142421__create_chat_room_foundation.sql";

    @Test
    void declaresRequiredChatRoomConstraints() throws IOException {
        String migration = readMigration();

        assertTrue(migration.contains("CONSTRAINT `UK_REGIONS_CODE` UNIQUE (`code`)"));
        assertTrue(migration.contains("CONSTRAINT `UK_CHAT_ROOMS_REGION` UNIQUE (`region_id`)"));
        assertTrue(migration.contains("`active_user_id` BIGINT GENERATED ALWAYS AS"));
        assertTrue(migration.contains("CASE WHEN `deleted_at` IS NULL THEN `user_id` ELSE NULL END"));
        assertTrue(migration.contains("CONSTRAINT `UK_CHAT_ROOM_MEMBERS_ACTIVE_USER` UNIQUE (`active_user_id`)"));
        assertTrue(migration.contains("`capacity` INT NOT NULL DEFAULT 25"));
        assertTrue(migration.contains("INDEX `IDX_CHAT_ROOM_MEMBERS_ROOM_ACTIVE` (`room_id`, `deleted_at`)"));
    }

    private String readMigration() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(MIGRATION_PATH)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
