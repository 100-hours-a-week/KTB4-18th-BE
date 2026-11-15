package com.muse.meomuneum.global.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MusicRecordSignupLocalFlywayConfigTest {
    private static final String SCHEMA = "meomuneum_music_record_signup_test";
    private static final String USER = "mm_signup_test";

    @Test
    void acceptsOnlyTheExpectedLocalTarget() {
        assertDoesNotThrow(() -> MusicRecordSignupLocalFlywayConfig.validateConfiguredTarget(
                "jdbc:mysql://localhost:3306/" + SCHEMA, USER, SCHEMA, USER));
        assertDoesNotThrow(() -> MusicRecordSignupLocalFlywayConfig.validateConfiguredTarget(
                "jdbc:mysql://127.0.0.1:3306/" + SCHEMA, USER, SCHEMA, USER));
    }

    @Test
    void rejectsExistingOrRemoteDatabaseBeforeMigration() {
        assertThrows(IllegalStateException.class, () -> MusicRecordSignupLocalFlywayConfig.validateConfiguredTarget(
                "jdbc:mysql://localhost:3306/meomuneum_test", USER, SCHEMA, USER));
        assertThrows(IllegalStateException.class, () -> MusicRecordSignupLocalFlywayConfig.validateConfiguredTarget(
                "jdbc:mysql://db.example.com:3306/" + SCHEMA, USER, SCHEMA, USER));
        assertThrows(IllegalStateException.class, () -> MusicRecordSignupLocalFlywayConfig.validateConfiguredTarget(
                "jdbc:mysql://localhost:3307/" + SCHEMA, USER, SCHEMA, USER));
        assertThrows(IllegalStateException.class, () -> MusicRecordSignupLocalFlywayConfig.validateConfiguredTarget(
                "jdbc:mysql://localhost:3306/" + SCHEMA, "meomuneum_test", SCHEMA, USER));
    }

    @Test
    void rejectsFlywayDatasourceOverrideBeforeMigration() {
        var environment = new MockEnvironment()
                .withProperty("spring.flyway.url", "jdbc:mysql://localhost:3306/meomuneum_test");
        environment.setActiveProfiles("test", "music-record-signup-local");

        assertThrows(IllegalStateException.class,
                () -> new MusicRecordSignupLocalFlywayConfig().musicRecordSignupLocalMigrationStrategy(
                        environment, "jdbc:mysql://localhost:3306/" + SCHEMA, USER));
    }
    @Test
    void acceptsOnlyPackagedSignupLocationBeforeCheckingConnectedTarget() throws Exception {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test", "music-record-signup-local");
        var strategy = new MusicRecordSignupLocalFlywayConfig().musicRecordSignupLocalMigrationStrategy(
                environment, "jdbc:mysql://localhost:3306/" + SCHEMA, USER);
        var source = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var statement = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        var result = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        org.mockito.Mockito.when(source.getConnection()).thenReturn(connection);
        org.mockito.Mockito.when(connection.prepareStatement("SELECT DATABASE(), CURRENT_USER()"))
                .thenReturn(statement);
        org.mockito.Mockito.when(statement.executeQuery()).thenReturn(result);
        org.mockito.Mockito.when(result.next()).thenReturn(true);
        org.mockito.Mockito.when(result.getString(1)).thenReturn(SCHEMA);
        org.mockito.Mockito.when(result.getString(2)).thenReturn(USER + "@localhost");
        var config = new org.flywaydb.core.api.configuration.ClassicConfiguration();
        config.setDataSource(source);
        config.setLocationsAsStrings("classpath:db/music-record-signup-legacy");
        var flyway = org.mockito.Mockito.mock(org.flywaydb.core.Flyway.class);
        org.mockito.Mockito.when(flyway.getConfiguration()).thenReturn(config);
        assertDoesNotThrow(() -> strategy.migrate(flyway));
        org.mockito.Mockito.verify(flyway).migrate();
        for (String location : java.util.List.of("filesystem:build/music-record-signup-local-migrations",
                "classpath:db/migration", "filesystem:src/main/resources/db/migration",
                "classpath:db/music-search-legacy")) {
            config.setLocationsAsStrings(location);
            assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));
        }
        org.mockito.Mockito.verify(flyway, org.mockito.Mockito.times(1)).migrate();
    }
}
