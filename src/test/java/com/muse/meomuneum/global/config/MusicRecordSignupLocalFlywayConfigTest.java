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
}
