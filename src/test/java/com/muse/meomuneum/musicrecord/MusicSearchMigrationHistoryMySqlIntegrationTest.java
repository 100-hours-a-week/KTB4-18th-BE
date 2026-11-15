package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.musicrecord.config.MusicSearchMigrationConfiguration;

@Testcontainers
class MusicSearchMigrationHistoryMySqlIntegrationTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("migration_test").withUsername("test").withPassword("test");
    @TempDir
    Path directory;

    @Test
    void exactImmutableOriginFreshAndAppliedOverlayPreserveHistoryAndMetadata() throws Exception {
        Path origin = MusicSearchMigrationFixtures.origin(directory.resolve("origin"));
        Path overlay = MusicSearchMigrationFixtures.overlay(origin, directory.resolve("overlay"));
        var fresh = new MusicSearchMigrationFixtures(MYSQL);
        var freshFlyway = fresh.flyway("filesystem:" + overlay, null);
        freshFlyway.migrate();
        freshFlyway.validate();
        fresh.assertAligned();
        assertThat(fresh.history()).hasSize(48);
        assertThat(fresh.history())
                .noneMatch(row -> MusicSearchMigrationFixtures.OLD_VERSION.equals(row.get("version")));

        var applied = new MusicSearchMigrationFixtures(MYSQL);
        applied.flyway("filesystem:" + origin, null).migrate();
        var prior = applied.history();
        applied.seed();
        applied.jdbc
                .update("UPDATE music_search_ingest_jobs SET title_us='US',artist_name_us='US artist',title_ko='한국', "
                        + "artist_name_ko='아티스트',release_epoch_second=123456789");
        var metadata = applied.jdbc.queryForList("SELECT title_us,artist_name_us,title_ko,artist_name_ko, "
                + "release_epoch_second FROM music_search_ingest_jobs ORDER BY id");
        var upgrade = applied.flyway("filesystem:" + overlay, null);
        upgrade.migrate();
        upgrade.validate();
        assertThat(applied.history().subList(0, prior.size())).isEqualTo(prior);
        assertThat(applied.history()).hasSize(prior.size() + 2);
        assertThat(applied.jdbc.queryForList("SELECT title_us,artist_name_us,title_ko,artist_name_ko, "
                + "release_epoch_second FROM music_search_ingest_jobs ORDER BY id")).isEqualTo(metadata);
        applied.assertAligned();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @MethodSource("legacyChains")
    void legacyCreatorAndRecoveryAppliedChainsPreserveHistoryAndData(String location, String target) {
        var fixture = new MusicSearchMigrationFixtures(MYSQL);
        fixture.flyway(location, MusicSearchMigrationFixtures.OLD_VERSION).migrate();
        fixture.seed();
        if (target.equals(MusicSearchMigrationFixtures.RECOVERY)) {
            fixture.flyway(location, target).migrate();
            fixture.jdbc.update("UPDATE music_search_ingest_dlq SET replayed_at='2026-02-01'");
        }
        var prior = fixture.history();
        var jobs = fixture.jdbc.queryForList("SELECT id,external_music_id,title,artist_name,album_cover_url, "
                + "preview_url,youtube_video_id,attempt_count,created_at,updated_at FROM music_search_ingest_jobs "
                + "ORDER BY id");
        var data = target.equals(MusicSearchMigrationFixtures.RECOVERY) ? fixture.data() : null;
        var upgrade = fixture.flyway(location, null);
        new MusicSearchMigrationConfiguration().musicSearchMigrationStrategy().migrate(upgrade);
        upgrade.validate();
        assertThat(fixture.history().subList(0, prior.size())).isEqualTo(prior);
        assertThat(fixture.history()).hasSize(prior.size()
                + (target.equals(MusicSearchMigrationFixtures.RECOVERY) ? 1 : 2));
        assertThat(fixture.jdbc.queryForList("SELECT id,external_music_id,title,artist_name,album_cover_url, "
                + "preview_url,youtube_video_id,attempt_count,created_at,updated_at FROM music_search_ingest_jobs "
                + "ORDER BY id")).isEqualTo(jobs);
        if (data != null) {
            assertThat(fixture.data()).isEqualTo(data);
        }
        assertThat(fixture.jdbc.queryForObject("SELECT status FROM music_search_ingest_jobs "
                + "WHERE external_music_id='FAILED'", String.class)).isEqualTo("DEAD");
        assertThat(fixture.jdbc.queryForList("SELECT status FROM music_search_ingest_jobs "
                + "WHERE external_music_id<>'FAILED' ORDER BY id", String.class))
                .containsExactly("QUEUED", "PROCESSING", "DONE");
        assertThat(fixture.jdbc.queryForObject("SELECT title FROM music_search_ingest_dlq", String.class))
                .isEqualTo("payload");
        assertThat(fixture.jdbc.queryForObject("SELECT attempt_started_at IS NOT NULL "
                + "FROM music_search_ingest_jobs WHERE status='PROCESSING'", Boolean.class)).isTrue();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM music_search_ingest_jobs WHERE title_us IS NULL "
                + "AND artist_name_us IS NULL AND title_ko IS NULL AND artist_name_ko IS NULL "
                + "AND release_epoch_second IS NULL", Integer.class)).isEqualTo(4);
        fixture.assertAligned();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    static Stream<Arguments> legacyChains() {
        return Stream.of(MusicSearchMigrationFixtures.LEGACY, "classpath:db/music-record-local",
                "classpath:db/music-record-signup-legacy").flatMap(
                        location -> Stream.of(
                                Arguments.of(location, MusicSearchMigrationFixtures.OLD_VERSION),
                                Arguments.of(location, MusicSearchMigrationFixtures.RECOVERY)));
    }

    @ParameterizedTest
    @MethodSource("incompatibleColumns")
    void rejectsEveryIncompatibleDefinitionBeforeAnyDdl(String column, String definition) {
        var fixture = new MusicSearchMigrationFixtures(MYSQL);
        fixture.flyway(MusicSearchMigrationFixtures.LEGACY, MusicSearchMigrationFixtures.RECOVERY).migrate();
        fixture.seed();
        fixture.jdbc.execute("ALTER TABLE music_search_ingest_jobs ADD COLUMN " + column + " " + definition);
        fixture.restrictMigrationPrivileges();
        var catalog = fixture.catalog();
        var history = fixture.history();
        var data = fixture.data();
        var flyway = fixture.flyway(MusicSearchMigrationFixtures.LEGACY, null);
        assertThatThrownBy(flyway::migrate).hasStackTraceContaining(
                "music_search_alignment_requires_expected_nullable_column_types");
        assertThat(fixture.catalog()).isEqualTo(catalog);
        assertThat(fixture.history()).isEqualTo(history);
        assertThat(fixture.data()).isEqualTo(data);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version=? AND success=FALSE", Integer.class, MusicSearchMigrationFixtures.ALIGNMENT))
                .isEqualTo(1);
    }

    static Stream<Arguments> incompatibleColumns() {
        return MusicSearchMigrationFixtures.COLUMNS.stream().flatMap(column -> {
            if (column.equals("release_epoch_second")) {
                return Stream.of(Arguments.of(column, "VARCHAR(255) NULL"),
                        Arguments.of(column, "BIGINT NOT NULL DEFAULT 0"));
            }
            int length = column.startsWith("artist") ? 1000 : 255;
            return Stream.of(Arguments.of(column, "BIGINT NULL"),
                    Arguments.of(column, "VARCHAR(" + length + ") NOT NULL DEFAULT ''"),
                    Arguments.of(column, "VARCHAR(" + (length - 1) + ") NULL"));
        });
    }

    @Test
    void partialCompatibleMetadataPreservesValuesAndAddsOnlyMissingFields() {
        var fixture = new MusicSearchMigrationFixtures(MYSQL);
        fixture.flyway(MusicSearchMigrationFixtures.LEGACY, MusicSearchMigrationFixtures.RECOVERY).migrate();
        fixture.seed();
        fixture.jdbc.execute("ALTER TABLE music_search_ingest_jobs ADD COLUMN title_us VARCHAR(255) NULL, "
                + "ADD COLUMN release_epoch_second BIGINT NULL");
        fixture.jdbc.update("UPDATE music_search_ingest_jobs SET title_us='preserved',release_epoch_second=123");
        fixture.restrictMigrationPrivileges();
        fixture.flyway(MusicSearchMigrationFixtures.LEGACY, null).migrate();
        fixture.assertAligned();
        assertThat(fixture.jdbc.queryForObject(
                "SELECT title_us FROM music_search_ingest_jobs WHERE external_music_id='QUEUED'", String.class))
                .isEqualTo("preserved");
        assertThat(fixture.jdbc.queryForObject(
                "SELECT release_epoch_second FROM music_search_ingest_jobs WHERE external_music_id='QUEUED'",
                Long.class)).isEqualTo(123L);
        assertThat(fixture.jdbc.queryForObject("SELECT artist_name_us IS NULL AND title_ko IS NULL "
                + "AND artist_name_ko IS NULL FROM music_search_ingest_jobs WHERE external_music_id='QUEUED'",
                Boolean.class)).isTrue();
    }

    @Test
    void guardUsesConfiguredHistoryTableAndSchemaBeforeDdlAndNativeValidationStillRejects() {
        var fixture = new MusicSearchMigrationFixtures(MYSQL);
        String schema = fixture.jdbc.queryForObject("SELECT DATABASE()", String.class);
        var otherDatabase = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var legacy = Flyway.configure().dataSource(otherDatabase).defaultSchema(schema).table("music_history")
                .locations(MusicSearchMigrationFixtures.LEGACY).target(MusicSearchMigrationFixtures.RECOVERY).load();
        legacy.migrate();
        var prior = fixture.jdbc.queryForList("SELECT * FROM music_history ORDER BY installed_rank");
        var catalog = fixture.catalog();
        var canonical = Flyway.configure().dataSource(otherDatabase).defaultSchema(schema).table("music_history")
                .locations(MusicSearchMigrationFixtures.CANONICAL).load();
        assertThatThrownBy(() -> new MusicSearchMigrationConfiguration().musicSearchMigrationStrategy()
                .migrate(canonical)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inspect Flyway history").hasMessageContaining("music-search-legacy");
        assertThatThrownBy(canonical::migrate).hasMessageContaining("Validate failed");
        assertThat(fixture.catalog()).isEqualTo(catalog);
        assertThat(fixture.jdbc.queryForList("SELECT * FROM music_history ORDER BY installed_rank")).isEqualTo(prior);
    }

    @Test
    void missingUnrelatedAppliedHistoryAndMixedCreatorLocationsFailNatively() throws Exception {
        Path chain = MusicSearchMigrationFixtures.copyLocation(MusicSearchMigrationFixtures.LEGACY,
                directory.resolve("unknown"));
        Files.writeString(chain.resolve("V20261115190900__unknown_applied.sql"), "SELECT 1;\n");
        var unknown = new MusicSearchMigrationFixtures(MYSQL);
        unknown.flyway("filesystem:" + chain, "20261115190900").migrate();
        var history = unknown.history();
        var catalog = unknown.catalog();
        assertThatThrownBy(() -> new MusicSearchMigrationConfiguration().musicSearchMigrationStrategy()
                .migrate(unknown.flyway(MusicSearchMigrationFixtures.LEGACY, null)))
                .hasMessageContaining("Unresolved applied Flyway history");
        assertThatThrownBy(() -> unknown.flyway(MusicSearchMigrationFixtures.LEGACY, null).migrate())
                .hasMessageContaining("Validate failed");
        assertThat(unknown.history()).isEqualTo(history);
        assertThat(unknown.catalog()).isEqualTo(catalog);

        var mixed = new MusicSearchMigrationFixtures(MYSQL);
        mixed.flyway(MusicSearchMigrationFixtures.LEGACY, MusicSearchMigrationFixtures.RECOVERY).migrate();
        var mixedHistory = mixed.history();
        var mixedCatalog = mixed.catalog();
        var flyway = Flyway.configure().dataSource(mixed.source)
                .locations(MusicSearchMigrationFixtures.CANONICAL, MusicSearchMigrationFixtures.LEGACY).load();
        assertThatThrownBy(flyway::migrate).hasMessageContaining("Validate failed")
                .hasMessageContaining("20261110193555");
        assertThat(mixed.history()).isEqualTo(mixedHistory);
        assertThat(mixed.catalog()).isEqualTo(mixedCatalog);
    }
    @Test
    void unknownFutureAppliedHistoryIsRejectedWithoutRelyingOnDefaultFutureIgnore() throws Exception {
        Path chain = MusicSearchMigrationFixtures.copyLocation(MusicSearchMigrationFixtures.LEGACY,
                directory.resolve("future"));
        Files.writeString(chain.resolve("V20990101000000__future_applied.sql"), "SELECT 1;\n");
        var fixture = new MusicSearchMigrationFixtures(MYSQL);
        fixture.flyway("filesystem:" + chain, null).migrate();
        var prior = fixture.history();
        var catalog = fixture.catalog();
        assertThatThrownBy(() -> new MusicSearchMigrationConfiguration().musicSearchMigrationStrategy()
                .migrate(fixture.flyway(MusicSearchMigrationFixtures.LEGACY, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unresolved applied Flyway history");
        assertThat(fixture.history()).isEqualTo(prior);
        assertThat(fixture.catalog()).isEqualTo(catalog);
    }

    @Test
    void allFrozenClasspathChainsContainExactlyTheirStagedBytesWithoutCanonicalCreator() throws Exception {
        for (String name : List.of("music-record-local", "music-record-signup-local", "music-search-legacy")) {
            String location = name.equals("music-record-signup-local") ? "music-record-signup-legacy" : name;
            Path copied = MusicSearchMigrationFixtures.copyLocation("classpath:db/" + location,
                    directory.resolve(name));
            Path staged = Path.of("build", name + "-migrations");
            try (var actual = Files.list(copied); var expected = Files.list(staged)) {
                assertThat(actual.map(path -> path.getFileName().toString()).sorted().toList())
                        .isEqualTo(expected.map(path -> path.getFileName().toString()).sorted().toList());
            }
            try (var paths = Files.list(staged)) {
                for (Path path : paths.toList()) {
                    assertThat(Files.readAllBytes(copied.resolve(path.getFileName())))
                            .isEqualTo(Files.readAllBytes(path));
                }
            }
            assertThat(Files.exists(copied.resolve("V20261110093705__create_music_search_ingest_jobs.sql"))).isTrue();
            assertThat(Files.exists(copied.resolve("V20261110193555__create_music_search_ingest_jobs.sql"))).isFalse();
        }
        assertThat(new org.springframework.core.io.ClassPathResource(
                "db/migration/V20261110093705__create_music_search_ingest_jobs.sql").exists()).isFalse();
    }
}
