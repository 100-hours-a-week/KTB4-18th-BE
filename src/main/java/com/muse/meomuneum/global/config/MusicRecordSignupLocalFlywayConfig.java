package com.muse.meomuneum.global.config;

import java.net.URI;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration
@Profile("music-record-signup-local")
public class MusicRecordSignupLocalFlywayConfig {
    private static final String DEV_SCHEMA = "meomuneum_music_record_signup_dev";
    private static final String TEST_SCHEMA = "meomuneum_music_record_signup_test";

    @Bean
    public FlywayMigrationStrategy musicRecordSignupLocalMigrationStrategy(
            Environment environment,
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username) {
        String[] activeProfiles = environment.getActiveProfiles();
        boolean dev = Arrays.asList(activeProfiles).contains("dev");
        boolean test = Arrays.asList(activeProfiles).contains("test");
        if (dev == test) {
            throw new IllegalStateException("Signup-local profile requires exactly one of dev or test");
        }
        String expectedSchema = dev ? DEV_SCHEMA : TEST_SCHEMA;
        String expectedUser = dev ? "mm_signup_dev" : "mm_signup_test";
        validateConfiguredTarget(url, username, expectedSchema, expectedUser);
        if (environment.getProperty("spring.flyway.url") != null
                || environment.getProperty("spring.flyway.user") != null
                || environment.getProperty("spring.flyway.password") != null) {
            throw new IllegalStateException("Signup-local Flyway datasource override is not allowed");
        }

        return flyway -> {
            if (flyway.getConfiguration().getLocations().length != 1
                    || !"filesystem:build/music-record-signup-local-migrations"
                            .equals(flyway.getConfiguration().getLocations()[0].toString())) {
                throw new IllegalStateException("Signup-local Flyway migration location is not isolated");
            }
            validateConnectedTarget(flyway.getConfiguration().getDataSource(), expectedSchema, expectedUser);
            flyway.migrate();
        };
    }

    static void validateConfiguredTarget(String jdbcUrl, String username, String schema, String user) {
        try {
            if (!jdbcUrl.startsWith("jdbc:mysql://")) {
                throw new IllegalArgumentException();
            }
            URI uri = URI.create(jdbcUrl.substring(5));
            String path = uri.getPath();
            if (!Set.of("localhost", "127.0.0.1").contains(uri.getHost())
                    || uri.getPort() != 3306
                    || !('/' + schema).equals(path)
                    || uri.getUserInfo() != null
                    || !user.equals(username)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Signup-local database target is not isolated");
        }
    }

    private static void validateConnectedTarget(DataSource dataSource, String schema, String user) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("SELECT DATABASE(), CURRENT_USER()");
                var result = statement.executeQuery()) {
            if (!result.next()
                    || !schema.equals(result.getString(1))
                    || !result.getString(2).startsWith(user + "@")) {
                throw new IllegalStateException("Signup-local database connection is not isolated");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Signup-local database preflight failed");
        }
    }
}
