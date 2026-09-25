package com.muse.meomuneum.chat.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class ChatRoomProvisioningMigrationMySqlTest {

    private static final String SEED_MIGRATION_PATH =
            "db/migration/V20260923162348__seed_chat_regions_and_rooms.sql";
    private static final List<String> PREREQUISITE_MIGRATION_PATHS = List.of(
            "db/migration/V20260921200528__create_users_table.sql",
            "db/migration/V20260922145107__add_auto_increment_to_users_id.sql",
            "db/migration/V20260923142421__create_chat_room_foundation.sql"
    );

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.6")
            .withDatabaseName("meomuneum_migration_test")
            .withUsername("meomuneum_test")
            .withPassword("meomuneum_test");

    @Test
    void keepsRegionAndRoomProvisioningIdempotentOnMySql() throws SQLException {
        for (String migrationPath : PREREQUISITE_MIGRATION_PATHS) {
            executeMigration(migrationPath);
        }

        executeMigration(SEED_MIGRATION_PATH);
        List<RegionState> regionsAfterFirstRun = readRegionStates();
        List<RoomState> roomsAfterFirstRun = readRoomStates();

        assertEquals(16, countRegionLevel(regionsAfterFirstRun, "SIDO"));
        assertEquals(269, countRegionLevel(regionsAfterFirstRun, "SIGUNGU"));
        assertEquals(269, roomsAfterFirstRun.size());
        assertTrue(roomsAfterFirstRun.stream()
                .allMatch(room -> room.capacity() == 25 && "ACTIVE".equals(room.status())));
        assertEquals(0, countMissingActiveSigunguRooms());
        assertEquals(269, countDistinctRoomRegions());

        executeMigration(SEED_MIGRATION_PATH);

        assertEquals(regionsAfterFirstRun, readRegionStates());
        assertEquals(roomsAfterFirstRun, readRoomStates());
        assertEquals(0, countMissingActiveSigunguRooms());
        assertEquals(269, countDistinctRoomRegions());
    }

    private void executeMigration(String migrationPath) throws SQLException {
        try (Connection connection = openConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(migrationPath));
        }
    }

    private List<RegionState> readRegionStates() throws SQLException {
        String sql = """
                SELECT region.code, region.name, region.level, parent.code, region.is_active
                FROM regions AS region
                LEFT JOIN regions AS parent ON parent.id = region.parent_id
                ORDER BY region.code
                """;
        List<RegionState> regions = new ArrayList<>();
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                regions.add(new RegionState(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        resultSet.getBoolean(5)
                ));
            }
        }
        return regions;
    }

    private List<RoomState> readRoomStates() throws SQLException {
        String sql = """
                SELECT id, region_id, capacity, status
                FROM chat_rooms
                ORDER BY id
                """;
        List<RoomState> rooms = new ArrayList<>();
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rooms.add(new RoomState(
                        resultSet.getLong(1),
                        resultSet.getLong(2),
                        resultSet.getInt(3),
                        resultSet.getString(4)
                ));
            }
        }
        return rooms;
    }

    private long countMissingActiveSigunguRooms() throws SQLException {
        return queryForCount("""
                SELECT COUNT(*)
                FROM regions AS region
                LEFT JOIN chat_rooms AS room ON room.region_id = region.id
                WHERE region.level = 'SIGUNGU'
                    AND region.is_active = TRUE
                    AND room.id IS NULL
                """);
    }

    private long countDistinctRoomRegions() throws SQLException {
        return queryForCount("SELECT COUNT(DISTINCT region_id) FROM chat_rooms");
    }

    private long queryForCount(String sql) throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private long countRegionLevel(List<RegionState> regions, String level) {
        return regions.stream().filter(region -> level.equals(region.level())).count();
    }

    private record RegionState(String code, String name, String level, String parentCode, boolean active) {
    }

    private record RoomState(long id, long regionId, int capacity, String status) {
    }
}
