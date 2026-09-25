package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"test", "music-record-local"})
class MusicRecordTestProfileIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void preparesMusicRecordSchemaWithoutChatMigration() {
        assertThat(tableExists("users")).isTrue();
        assertThat(tableExists("music_records")).isTrue();
        assertThat(tableExists("map_dots")).isTrue();
        assertThat(tableExists("chat_rooms")).isFalse();
    }

    private boolean tableExists(String name) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, name);
        return count != null && count == 1;
    }
}
