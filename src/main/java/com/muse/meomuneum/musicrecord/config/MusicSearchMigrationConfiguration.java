package com.muse.meomuneum.musicrecord.config;

import java.util.Arrays;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!music-record-signup-local")
public class MusicSearchMigrationConfiguration {
    private static final String LEGACY_CREATOR = "20261110093705";

    @Bean
    public FlywayMigrationStrategy musicSearchMigrationStrategy() {
        return flyway -> {
            var migrations = flyway.info().all();
            boolean legacyApplied = Arrays.stream(migrations)
                    .anyMatch(migration -> migration.getVersion() != null
                            && LEGACY_CREATOR.equals(migration.getVersion().getVersion())
                            && migration.getState().isApplied());
            boolean legacyResolved = Arrays.stream(migrations)
                    .anyMatch(migration -> migration.getVersion() != null
                            && LEGACY_CREATOR.equals(migration.getVersion().getVersion())
                            && migration.getState().isResolved());
            if (legacyApplied && !legacyResolved) {
                throw new IllegalStateException("Legacy music-search creator is already applied. Inspect Flyway "
                        + "history and deliberately select the matching frozen profile: music-search-legacy, "
                        + "music-record-local or music-record-signup-local. Preserve SQL and history; "
                        + "do not repair, baseline or bypass validation.");
            }
            boolean unresolvedApplied = Arrays.stream(migrations)
                    .anyMatch(migration -> migration.getState().isApplied() && !migration.getState().isResolved());
            if (unresolvedApplied) {
                throw new IllegalStateException("Unresolved applied Flyway history. Inspect history and select "
                        + "the exact matching migration chain before startup; unknown histories are unsupported.");
            }
            flyway.migrate();
        };
    }
}
