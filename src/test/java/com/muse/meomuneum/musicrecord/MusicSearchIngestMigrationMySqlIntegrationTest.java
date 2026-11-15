package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class MusicSearchIngestMigrationMySqlIntegrationTest {
    private static final String MIGRATION_VERSION = "20261115190842";
    private static final String LOCAL_DATABASE = "music_record_local_test";
    private static final String SIGNUP_LOCAL_DATABASE = "music_record_signup_local_test";

    @Container
    private static final MySQLContainer CANONICAL_MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("music_canonical_test").withUsername("test").withPassword("test");
    @Container
    private static final MySQLContainer LOCAL_MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName(LOCAL_DATABASE).withUsername("test").withPassword("test");
    @Container
    private static final MySQLContainer SIGNUP_LOCAL_MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName(SIGNUP_LOCAL_DATABASE).withUsername("test").withPassword("test");

    @Test
    void canonicalFlywayMigratesTheNewJobTableOnAnEmptyDatabase() {
        Flyway flyway = Flyway.configure().dataSource(CANONICAL_MYSQL.getJdbcUrl(),
                CANONICAL_MYSQL.getUsername(), CANONICAL_MYSQL.getPassword())
                .locations("classpath:db/migration").load();

        flyway.migrate();

        assertThat(tableExists(CANONICAL_MYSQL, "music_search_ingest_jobs")).isTrue();
        assertThat(tableExists(CANONICAL_MYSQL, "music_search_ingest_dlq")).isTrue();
        assertThat(migrationApplied(CANONICAL_MYSQL)).isTrue();
        assertThat(indexNames(CANONICAL_MYSQL)).contains("uq_music_search_ingest_jobs_provider_external",
                "idx_music_search_ingest_jobs_due", "idx_music_search_ingest_jobs_lease");
    }

    @Test
    void preparedMusicRecordLocalMigrationsApplyToAnEmptyDatabase() throws Exception {
        Path migration = Path.of("build/music-record-local-migrations",
                "V" + MIGRATION_VERSION + "__add_music_search_storage_recovery.sql");
        assertThat(Files.isRegularFile(migration)).isTrue();

        Flyway localFlyway = Flyway.configure().dataSource(LOCAL_MYSQL.getJdbcUrl(),
                LOCAL_MYSQL.getUsername(), LOCAL_MYSQL.getPassword())
                .locations("filesystem:build/music-record-local-migrations").load();

        localFlyway.migrate();

        assertThat(tableExists(LOCAL_MYSQL, "music_search_ingest_jobs")).isTrue();
        assertThat(migrationApplied(LOCAL_MYSQL)).isTrue();
        assertThat(indexNames(LOCAL_MYSQL)).contains("uq_music_search_ingest_jobs_provider_external",
                "idx_music_search_ingest_jobs_due", "idx_music_search_ingest_jobs_lease");
    }

    @Test
    void preparedSignupLocalMigrationsApplyTheJobTableToAnEmptyDatabase() throws Exception {
        Path migration = Path.of("build/music-record-signup-local-migrations",
                "V" + MIGRATION_VERSION + "__add_music_search_storage_recovery.sql");
        assertThat(Files.isRegularFile(migration)).isTrue();

        Flyway signupLocalFlyway = Flyway.configure().dataSource(SIGNUP_LOCAL_MYSQL.getJdbcUrl(),
                SIGNUP_LOCAL_MYSQL.getUsername(), SIGNUP_LOCAL_MYSQL.getPassword())
                .locations("filesystem:build/music-record-signup-local-migrations").load();

        signupLocalFlyway.migrate();

        assertThat(tableExists(SIGNUP_LOCAL_MYSQL, "music_search_ingest_jobs")).isTrue();
        assertThat(migrationApplied(SIGNUP_LOCAL_MYSQL)).isTrue();
        assertThat(indexNames(SIGNUP_LOCAL_MYSQL)).contains("uq_music_search_ingest_jobs_provider_external",
                "idx_music_search_ingest_jobs_due", "idx_music_search_ingest_jobs_lease");
    }

    @Test
    void legacyFailedPayloadAndConservativeObservationSurviveUpgrade() throws Exception {
        try (var mysql = new MySQLContainer("mysql:9.7.0")
                .withDatabaseName("upgrade_test").withUsername("test").withPassword("test")) {
            mysql.start();
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
            try (var connection = source.getConnection()) {
                for (String file : List.of("V1__recommendation_sample.sql",
                        "V20261002133615__expand_music_artist_name_length.sql",
                        "V20261110093705__create_music_search_ingest_jobs.sql")) {
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                            new org.springframework.core.io.ClassPathResource("db/music-search-legacy/" + file));
                }
                jdbc.update("INSERT INTO music(provider,external_music_id,title,artist_name,created_at,updated_at) "
                        + "VALUES ('ITUNES','1','new metadata','artist','2026-01-02','2026-01-02')");
                for (String status : List.of("QUEUED", "PROCESSING", "DONE", "FAILED")) {
                    jdbc.update("INSERT INTO music_search_ingest_jobs(provider,external_music_id,title,artist_name, "
                            + "status,attempt_count,next_attempt_at,created_at,updated_at,lease_token,lease_until) "
                            + "VALUES ('ITUNES',?,'legacy','artist',?,3,CURRENT_TIMESTAMP(6), "
                            + "'2026-01-01','2026-01-03','old-token','2026-01-03')",
                            "QUEUED".equals(status) ? "1" : status, status);
                }
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/V20261115190842__add_music_search_storage_recovery.sql"));
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/V20261115204849__align_music_search_ingest_metadata_columns.sql"));
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/V20261115233805__align_music_catalog_compatibility.sql"));
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/V20261115233818__extend_music_search_dlq_payload.sql"));
            }
            assertThat(jdbc.queryForObject("SELECT title FROM music_search_ingest_dlq", String.class))
                    .isEqualTo("legacy");
            assertThat(jdbc.queryForObject("SELECT status FROM music_search_ingest_jobs "
                    + "WHERE external_music_id='FAILED'", String.class)).isEqualTo("DEAD");
            assertThat(jdbc.queryForObject("SELECT attempt_started_at IS NOT NULL FROM music_search_ingest_jobs "
                    + "WHERE external_music_id='PROCESSING'", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT j.metadata_observed_at<m.metadata_observed_at "
                    + "FROM music_search_ingest_jobs j CROSS JOIN music m WHERE j.external_music_id='1'",
                    Boolean.class)).isTrue();
            var observed = jdbc.queryForObject("SELECT metadata_observed_at FROM music_search_ingest_jobs "
                    + "WHERE external_music_id='1'", java.time.LocalDateTime.class);
            var music = new com.muse.meomuneum.musicrecord.repository.MusicRecordRepository(jdbc);
            music.upsertSearchMusic(new com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem(
                    null, "ITUNES", "1", "legacy", "artist", null, null, null, false), observed, 1);
            assertThat(music.findMusic("ITUNES", "1").orElseThrow().title()).isEqualTo("new metadata");
        }
    }

    private boolean tableExists(MySQLContainer mysql, String table) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table);
        return count != null && count == 1;
    }

    private boolean migrationApplied(MySQLContainer mysql) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version=? AND success=TRUE", Integer.class, MIGRATION_VERSION);
        return count != null && count == 1;
    }

    private List<String> indexNames(MySQLContainer mysql) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
        return jdbc.queryForList("SELECT index_name FROM information_schema.statistics "
                + "WHERE table_schema=? AND table_name=?", String.class, mysql.getDatabaseName(),
                "music_search_ingest_jobs");
    }

}
