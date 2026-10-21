package com.muse.meomuneum.chat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ChatRoomCapacityMigrationMySqlTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("chat_capacity_migration").withUsername("test").withPassword("test");

    @Test
    void upgradesExistingRoomsPreservingIdentityStateAndParticipationAndSetsInsertDefault() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("20261016090922").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        Long room = jdbc.queryForObject("SELECT MIN(id) FROM chat_rooms", Long.class);
        jdbc.update("UPDATE chat_rooms SET capacity=10,status='INACTIVE' WHERE id=?", room);
        var before = jdbc.queryForList("SELECT id,region_id,status,created_at,updated_at FROM chat_rooms ORDER BY id");
        jdbc.update("INSERT INTO users (email,password_hash,nickname,role) "
                + "VALUES ('capacity@example.com','test-only','정원검증','USER')");
        Long user = jdbc.queryForObject("SELECT id FROM users WHERE email='capacity@example.com'", Long.class);
        jdbc.update("INSERT INTO chat_room_members (user_id,room_id,auth_session_id) VALUES (?,?,?)",
                user, room, "550e8400-e29b-41d4-a716-446655440000");
        var members = jdbc.queryForList("SELECT * FROM chat_room_members");

        Flyway flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_rooms WHERE capacity<>1000", Integer.class));
        assertEquals(before,
                jdbc.queryForList("SELECT id,region_id,status,created_at,updated_at FROM chat_rooms ORDER BY id"));
        assertEquals(members, jdbc.queryForList("SELECT * FROM chat_room_members"));
        assertEquals("1000", jdbc.queryForObject("""
                SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='chat_rooms' AND COLUMN_NAME='capacity'
                """, String.class));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertTrue(flyway.validateWithResult().validationSuccessful);

        Long parent = jdbc.queryForObject("SELECT id FROM regions WHERE code='41'", Long.class);
        jdbc.update("INSERT INTO regions (code,name,level,parent_id) VALUES ('capacity-default','기본값시험','SIGUNGU',?)",
                parent);
        Long region = jdbc.queryForObject("SELECT id FROM regions WHERE code='capacity-default'", Long.class);
        jdbc.update("INSERT INTO chat_rooms (region_id) VALUES (?)", region);
        assertEquals(1000, jdbc.queryForObject("SELECT capacity FROM chat_rooms WHERE region_id=?", Integer.class,
                region));
    }
}
