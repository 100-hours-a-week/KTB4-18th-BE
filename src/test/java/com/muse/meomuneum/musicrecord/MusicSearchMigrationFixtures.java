package com.muse.meomuneum.musicrecord;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

final class MusicSearchMigrationFixtures {
    static final String ORIGIN = "c199b8f67bdf435a5c45adea11a39e5a58eaa864";
    static final String LEGACY = "classpath:db/music-search-legacy";
    static final String CANONICAL = "classpath:db/migration";
    static final String OLD_VERSION = "20261110093705";
    static final String RECOVERY = "20261115190842";
    static final String ALIGNMENT = "20261115204849";
    static final List<String> COLUMNS = List.of("title_us", "artist_name_us", "title_ko", "artist_name_ko",
            "release_epoch_second");
    final DriverManagerDataSource source;
    final JdbcTemplate jdbc;
    private final JdbcTemplate root;
    private final String schema;

    MusicSearchMigrationFixtures(MySQLContainer mysql) {
        schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        root = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword()));
        root.execute("CREATE DATABASE " + schema);
        root.execute("GRANT ALL PRIVILEGES ON "
                + schema + ".* TO 'test'@'%'");
        String url = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + schema);
        source = new DriverManagerDataSource(url, mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(source);
    }

    void restrictMigrationPrivileges() {
        root.execute("REVOKE CREATE ROUTINE, ALTER ROUTINE, EXECUTE, CREATE TEMPORARY TABLES ON "
                + schema + ".* FROM 'test'@'%'");
        assertThat(jdbc.queryForList("SHOW GRANTS", String.class).toString())
                .doesNotContain("CREATE ROUTINE", "ALTER ROUTINE", "CREATE TEMPORARY TABLES", "EXECUTE");
    }

    Flyway flyway(String location, String target) {
        var configuration = Flyway.configure().dataSource(source).locations(location);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    List<Map<String, Object>> history() {
        return jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank");
    }

    List<Map<String, Object>> catalog() {
        return jdbc.queryForList("SELECT * FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name='music_search_ingest_jobs' ORDER BY ordinal_position");
    }

    void assertAligned() {
        for (String column : COLUMNS) {
            var definition = jdbc.queryForMap("SELECT data_type,character_maximum_length,is_nullable "
                    + "FROM information_schema.columns WHERE table_schema=DATABASE() "
                    + "AND table_name='music_search_ingest_jobs' AND column_name=?", column);
            assertThat(definition.get("IS_NULLABLE")).isEqualTo("YES");
            assertThat(definition.get("DATA_TYPE")).isEqualTo(column.equals("release_epoch_second")
                    ? "bigint"
                    : "varchar");
            assertThat(definition.get("CHARACTER_MAXIMUM_LENGTH")).isEqualTo(column.equals("release_epoch_second")
                    ? null
                    : Long.valueOf(column.startsWith("artist") ? 1000 : 255));
        }
    }

    void seed() {
        jdbc.update("INSERT INTO music(provider,external_music_id,title,artist_name,created_at,updated_at) "
                + "VALUES ('ITUNES','fixture','original','artist','2026-01-01','2026-01-02')");
        for (String status : List.of("QUEUED", "PROCESSING", "DONE", "FAILED")) {
            jdbc.update("INSERT INTO music_search_ingest_jobs(provider,external_music_id,title,artist_name, "
                    + "album_cover_url,preview_url,youtube_video_id,status,attempt_count,next_attempt_at, "
                    + "created_at,updated_at,lease_token,lease_until,last_error_code) "
                    + "VALUES ('ITUNES',?,'payload','artist','cover','preview','video',?,3,'2026-01-02', "
                    + "'2026-01-01','2026-01-02','lease','2026-01-03','legacy_error')", status, status);
        }
    }

    Map<String, List<Map<String, Object>>> data() {
        Map<String, List<Map<String, Object>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("music", "music_search_ingest_jobs", "music_search_ingest_dlq")) {
            var rows = jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id");
            if (table.equals("music_search_ingest_jobs")) {
                rows.forEach(row -> COLUMNS.forEach(row::remove));
            }
            snapshot.put(table, rows);
        }
        return snapshot;
    }

    static Path copyLocation(String location, Path directory) throws IOException {
        Files.createDirectories(directory);
        var resolver = new PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources(location.replace("classpath:", "classpath*:") + "/*.sql")) {
            try (var input = resource.getInputStream()) {
                Files.write(directory.resolve(resource.getFilename()), input.readAllBytes());
            }
        }
        return directory;
    }

    static Path origin(Path directory) throws Exception {
        Files.createDirectories(directory);
        String prefix = "src/main/resources/db/migration/";
        String listing = new String(git("ls-tree", "-r", "--name-only", ORIGIN, "--", prefix),
                StandardCharsets.UTF_8);
        var manifest = new StringBuilder("origin=" + ORIGIN + "\n");
        int count = 0;
        for (String path : listing.lines().filter(path -> path.endsWith(".sql")).toList()) {
            byte[] bytes = git("show", ORIGIN + ":" + path);
            String name = Path.of(path).getFileName().toString();
            Files.write(directory.resolve(name), bytes);
            manifest.append(name).append(' ').append(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes))).append('\n');
            count++;
        }
        assertThat(count).isEqualTo(46);
        Path report = Path.of("build/reports/music-search-migration/origin-manifest.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, manifest);
        return directory;
    }

    static Path overlay(Path origin, Path directory) throws Exception {
        Files.createDirectories(directory);
        try (var paths = Files.list(origin)) {
            for (Path path : paths.toList()) {
                Files.copy(path, directory.resolve(path.getFileName()));
            }
        }
        var resolver = new PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath*:db/migration/*.sql")) {
            String name = resource.getFilename();
            if (name.startsWith("V" + RECOVERY) || name.startsWith("V" + ALIGNMENT)) {
                try (var input = resource.getInputStream()) {
                    Files.write(directory.resolve(name), input.readAllBytes());
                }
            }
            if (name.startsWith("V20261110193555")) {
                try (var input = resource.getInputStream()) {
                    assertThat(input.readAllBytes()).isEqualTo(Files.readAllBytes(origin.resolve(name)));
                }
            }
        }
        return directory;
    }

    private static byte[] git(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        byte[] bytes = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("Required immutable origin object unavailable; fetch verified origin "
                    + ORIGIN + " before running migration tests");
        }
        return bytes;
    }
}
