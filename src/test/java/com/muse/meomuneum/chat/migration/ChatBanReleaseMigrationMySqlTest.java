package com.muse.meomuneum.chat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ChatBanReleaseMigrationMySqlTest {
    private static final String MIGRATION = "db/migration/V20261010155558__release_legacy_automatic_chat_bans.sql";
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("chat_ban_release_test").withUsername("test").withPassword("test");

    @Test
    void releasesOnlyActiveAutomaticBansAndPreservesHistoryAndManualBans() throws SQLException {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword()); Statement statement = connection.createStatement()) {
            for (String path : List.of("db/migration/V20260921200528__create_users_table.sql",
                    "db/migration/V20260922145107__add_auto_increment_to_users_id.sql",
                    "db/migration/V20261007205453__create_chat_bans_table.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(path));
            }
            statement.executeUpdate("""
                    INSERT INTO users (email, password_hash, nickname, role)
                    VALUES ('ban-release@example.com', 'test-only', '검증', 'USER')
                    """);
            statement.executeUpdate("""
                    INSERT INTO chat_bans (user_id, banned_by_user_id, reason, created_at, expires_at, deleted_at)
                    VALUES (1, NULL, 'PROFANITY', UTC_TIMESTAMP() - INTERVAL 1 DAY,
                        UTC_TIMESTAMP() + INTERVAL 6 DAY, NULL),
                        (1, NULL, 'OBSCENITY', UTC_TIMESTAMP() - INTERVAL 1 DAY,
                        UTC_TIMESTAMP() + INTERVAL 6 DAY, NULL),
                        (1, 1, 'PROFANITY', UTC_TIMESTAMP() - INTERVAL 1 DAY,
                        UTC_TIMESTAMP() + INTERVAL 6 DAY, NULL),
                        (1, NULL, 'PROFANITY', UTC_TIMESTAMP() - INTERVAL 8 DAY,
                        UTC_TIMESTAMP() - INTERVAL 1 DAY, NULL),
                        (1, NULL, 'OBSCENITY', UTC_TIMESTAMP() - INTERVAL 1 DAY,
                        UTC_TIMESTAMP() + INTERVAL 6 DAY, UTC_TIMESTAMP() - INTERVAL 1 HOUR),
                        (1, NULL, 'PROFANITY', UTC_TIMESTAMP() + INTERVAL 1 DAY,
                        UTC_TIMESTAMP() + INTERVAL 8 DAY, NULL)
                    """);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
            List<BanState> first = readBans(statement);
            assertNotNull(first.get(0).deletedAt());
            assertNotNull(first.get(1).deletedAt());
            assertNull(first.get(2).deletedAt());
            assertNull(first.get(3).deletedAt());
            assertNotNull(first.get(4).deletedAt());
            assertNull(first.get(5).deletedAt());
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM chat_bans WHERE TIMESTAMPDIFF(DAY, created_at, expires_at) = 7")) {
                rows.next();
                assertEquals(6, rows.getInt(1));
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
            assertEquals(first, readBans(statement));
        }
    }

    private List<BanState> readBans(Statement statement) throws SQLException {
        List<BanState> states = new java.util.ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id, reason, deleted_at FROM chat_bans ORDER BY id")) {
            while (rows.next()) {
                states.add(new BanState(rows.getLong(1), rows.getString(2), rows.getString(3)));
            }
        }
        return states;
    }

    private record BanState(long id, String reason, String deletedAt) {
    }
}
