package com.muse.meomuneum.chat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ChatDeviceMembershipMigrationMySqlTest {

    private static final String MIGRATION = "db/migration/V20261016090922__add_auth_session_to_chat_memberships.sql";
    private static final String SESSION_A = "00000000-0000-4000-8000-000000000001";
    private static final String SESSION_B = "00000000-0000-4000-8000-000000000002";
    private static final String VIEW_ID = "00000000-0000-4000-8000-000000000003";

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("chat_device_migration").withUsername("chat_test").withPassword("chat_test")
            // Default FK mode skips checks on legacy (foreign_key, nullable_deleted_at) indexes.
            // See https://bugs.mysql.com/bug.php?id=121438; production configuration is a separate task.
            .withCommand("--innodb-native-foreign-keys=ON");

    @BeforeEach
    void createLegacySchema() throws SQLException {
        for (String table : List.of("chat_view_intervals", "chat_view_sessions", "chat_room_members",
                "chat_rooms", "regions", "users", "flyway_schema_history")) {
            execute("DROP TABLE IF EXISTS " + table);
        }
        for (String migration : List.of(
                "V20260921200528__create_users_table.sql",
                "V20260922145107__add_auto_increment_to_users_id.sql",
                "V20260923142421__create_chat_room_foundation.sql",
                "V20261013024123__create_chat_view_sessions_and_intervals.sql")) {
            migrate("db/migration/" + migration);
        }
        execute("""
                INSERT INTO users (id,email,password_hash,nickname)
                VALUES (1,'one@example.test','fixture','one'), (2,'two@example.test','fixture','two')
                """);
        execute("INSERT INTO regions (id,code,name,level) VALUES (1,'one','one','SIGUNGU'),"
                + " (2,'two','two','SIGUNGU')");
        execute("INSERT INTO chat_rooms (id,region_id) VALUES (1,1),(2,2)");
    }

    @Test
    void preservesLegacyMembershipsAndViewForeignKeys() throws SQLException {
        execute("""
                INSERT INTO chat_room_members (id,user_id,room_id,created_at,deleted_at)
                VALUES (10,1,1,'2026-10-15 10:00:00',NULL),
                       (11,1,2,'2026-10-14 10:00:00','2026-10-14 11:00:00')
                """);
        execute("INSERT INTO chat_view_sessions (id,user_id,room_id,membership_id,created_at,updated_at)"
                + " VALUES ('" + VIEW_ID + "',1,1,10,'2026-10-15 10:00:00','2026-10-15 10:00:05')");
        execute("INSERT INTO chat_view_intervals (view_session_id,process_id,started_at,"
                + "start_after_message_id,verified_at,verified_through_message_id) VALUES ('" + VIEW_ID
                + "','" + SESSION_A + "','2026-10-15 10:00:00',0,'2026-10-15 10:00:05',7)");
        String before = membershipSnapshot();

        migrate(MIGRATION);

        assertEquals(before, membershipSnapshot());
        assertEquals(2, count("SELECT COUNT(*) FROM chat_room_members WHERE auth_session_id IS NULL"));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM chat_view_intervals i
                JOIN chat_view_sessions v ON v.id=i.view_session_id
                JOIN chat_room_members m ON m.id=v.membership_id
                WHERE m.id=10 AND v.user_id=1 AND v.room_id=1
                    AND i.verified_through_message_id=7
                """));
        assertRejected("DELETE FROM chat_room_members WHERE id=10", 1451);
    }

    @Test
    void retainsLegacyInsertAndUpdateUniqueness() throws SQLException {
        migrate(MIGRATION);
        execute("INSERT INTO chat_room_members (user_id,room_id) VALUES (1,1),(2,2)");
        assertRejected("INSERT INTO chat_room_members (user_id,room_id) VALUES (1,2)", 1062);
        assertRejected("UPDATE chat_room_members SET user_id=1 WHERE user_id=2", 1062);
        execute("UPDATE chat_room_members SET deleted_at=CURRENT_TIMESTAMP WHERE user_id=1");
        execute("INSERT INTO chat_room_members (user_id,room_id) VALUES (1,2)");
        assertEquals(3, count("SELECT COUNT(*) FROM chat_room_members"));
    }

    @Test
    void allowsDistinctSessionsOfOneAccountInSameOrDifferentRooms() throws SQLException {
        migrate(MIGRATION);
        execute(insert(1, 1, SESSION_A));
        execute(insert(1, 2, SESSION_B));
        execute("UPDATE chat_room_members SET room_id=1 WHERE auth_session_id='" + SESSION_B + "'");
        assertEquals(2, count("SELECT COUNT(*) FROM chat_room_members WHERE user_id=1 AND room_id=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM chat_room_members WHERE active_user_id IS NOT NULL"));
    }

    @Test
    void rejectsDuplicateActiveSessionAndReleasesItAfterLeave() throws SQLException {
        migrate(MIGRATION);
        execute(insert(1, 1, SESSION_A));
        assertRejected(insert(1, 2, SESSION_A), 1062);
        assertRejected(insert(2, 2, SESSION_A), 1062);
        execute(insert(1, 2, SESSION_B));
        assertRejected("UPDATE chat_room_members SET auth_session_id='" + SESSION_A
                + "' WHERE auth_session_id='" + SESSION_B + "'", 1062);
        execute("UPDATE chat_room_members SET deleted_at=CURRENT_TIMESTAMP WHERE auth_session_id='"
                + SESSION_A + "'");
        execute(insert(1, 2, SESSION_A));
        assertEquals(2, count("SELECT COUNT(*) FROM chat_room_members WHERE auth_session_id='" + SESSION_A + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM chat_room_members WHERE active_auth_session_id='"
                + SESSION_A + "'"));
    }

    @Test
    void enforcesConstraintsWhenRestoringOrConvertingLegacyMemberships() throws SQLException {
        migrate(MIGRATION);
        execute("INSERT INTO chat_room_members (user_id,room_id) VALUES (1,1)");
        execute(insert(1, 2, SESSION_A));
        assertRejected("UPDATE chat_room_members SET auth_session_id=NULL WHERE auth_session_id='"
                + SESSION_A + "'", 1062);
        execute("UPDATE chat_room_members SET deleted_at=CURRENT_TIMESTAMP WHERE auth_session_id IS NULL");
        execute("UPDATE chat_room_members SET auth_session_id=NULL WHERE auth_session_id='" + SESSION_A + "'");
        assertRejected("UPDATE chat_room_members SET deleted_at=NULL WHERE deleted_at IS NOT NULL", 1062);
        assertEquals(1, count("SELECT COUNT(*) FROM chat_room_members WHERE deleted_at IS NULL"));
    }

    @Test
    void rejectsMalformedSessionsAndPreservesUserAndRoomForeignKeys() throws SQLException {
        migrate(MIGRATION);
        for (String invalid : List.of("", "invalid", "00000000-0000-4000-8000-00000000000A")) {
            assertRejected(insert(1, 1, invalid), 3819);
        }
        assertEquals(1, count("SELECT @@foreign_key_checks"));
        assertEquals(2, count("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS"
                + " WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='chat_room_members'"));
        assertRejected(insert(999, 1, SESSION_A), 1452);
        assertRejected(insert(1, 999, SESSION_A), 1452);
        assertEquals(0, count("SELECT COUNT(*) FROM chat_room_members"));
    }

    @Test
    void acceptsOnlyOneConcurrentInsertForTheSameSession() throws Exception {
        migrate(MIGRATION);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> insertAfter(start, SESSION_A, 1));
            var second = executor.submit(() -> insertAfter(start, SESSION_A, 2));
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM chat_room_members"));
        execute(insert(1, 2, SESSION_B));
        assertEquals(2, count("SELECT COUNT(*) FROM chat_room_members"));
    }

    @Test
    void appliesOnceThroughFlywayAndValidatesHistory(@TempDir Path directory) throws IOException, SQLException {
        execute("INSERT INTO chat_room_members (user_id,room_id) VALUES (1,1)");
        try (var input = new ClassPathResource(MIGRATION).getInputStream()) {
            Files.copy(input, directory.resolve(Path.of(MIGRATION).getFileName()));
        }
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + directory)
                .baselineOnMigrate(true).baselineVersion("20261013024123").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(1, count("SELECT COUNT(*) FROM chat_room_members WHERE active_user_id=1"));
    }

    private int insertAfter(CountDownLatch start, String session, long room) throws Exception {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            execute(insert(1, room, session));
            return 1;
        } catch (SQLException exception) {
            assertEquals(1062, exception.getErrorCode());
            return 0;
        }
    }

    private String insert(long user, long room, String session) {
        return "INSERT INTO chat_room_members (user_id,room_id,auth_session_id) VALUES ("
                + user + "," + room + ",'" + session + "')";
    }

    private void assertRejected(String sql, int errorCode) {
        SQLException exception = assertThrows(SQLException.class, () -> execute(sql), sql);
        assertEquals(errorCode, exception.getErrorCode());
    }

    private String membershipSnapshot() throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT GROUP_CONCAT(CONCAT_WS('|',id,user_id,room_id,created_at,
                            COALESCE(CAST(deleted_at AS CHAR),'NULL')) ORDER BY id) FROM chat_room_members
                        """)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void migrate(String path) throws SQLException {
        try (Connection connection = openConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(path));
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
