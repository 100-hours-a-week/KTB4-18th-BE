package com.muse.meomuneum.music.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A one-shot operator tool; starts neither Spring, a web server nor Flyway. */
public final class MusicCatalogCommand {
    private MusicCatalogCommand() {
    }

    public static void main(String[] args) {
        try {
            execute(args);
        } catch (Exception exception) {
            // Deliberately omit provider/driver messages, URIs, review data and credentials.
            throw new IllegalStateException("catalog command failed; check manifest, metadata and database version");
        }
    }

    private static void execute(String[] args) throws Exception {
        if (args.length != 2 || !("verify".equals(args[0]) || "disable".equals(args[0]))
                || !"write-reviewed-source".equals(System.getenv("CATALOG_CONFIRM"))) {
            throw new IllegalArgumentException("explicit command confirmation required");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(Path.of(args[1]))) {
            bytes = input.readNBytes(16385);
        }
        if (bytes.length > 16384) {
            throw new IllegalArgumentException("manifest too large");
        }
        JsonNode manifest = new ObjectMapper().readTree(bytes);
        var fields = "verify".equals(args[0])
                ? Set.of("music_id", "video_id", "channel_id", "channel_kind", "evidence", "reviewer",
                        "valid_days", "expected_version")
                : Set.of("music_id", "expected_version");
        if (!manifest.isObject() || !manifest.propertyNames().equals(fields)) {
            throw new IllegalArgumentException("invalid manifest fields");
        }
        long musicId = integer(manifest, "music_id");
        long version = integer(manifest, "expected_version");
        MusicPlaybackSourceRepository.Review review = null;
        YouTubeVideoVerifier.Video video = null;
        if ("verify".equals(args[0])) {
            review = new MusicPlaybackSourceRepository.Review(musicId, text(manifest, "video_id"),
                    text(manifest, "channel_id"), text(manifest, "channel_kind"), text(manifest, "evidence"),
                    text(manifest, "reviewer"), Math.toIntExact(integer(manifest, "valid_days")), version);
            review.validate();
            // Network I/O finishes before opening a database transaction.
            video = new YouTubeVideoVerifier(required("YOUTUBE_DATA_API_KEY"))
                    .verify(review.videoId(), review.channelId());
        }
        String url = required("CATALOG_DATABASE_URL");
        if (!url.startsWith("jdbc:mysql://") || url.contains("allowMultiQueries=true")) {
            throw new IllegalArgumentException("MySQL catalog datasource required");
        }
        var dataSource = new DriverManagerDataSource(url, required("CATALOG_DATABASE_USER"),
                required("CATALOG_DATABASE_PASSWORD"));
        var properties = new java.util.Properties();
        properties.setProperty("connectTimeout", "2000");
        properties.setProperty("socketTimeout", "5000");
        dataSource.setConnectionProperties(properties);
        var repository = new MusicPlaybackSourceRepository(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
        if (review == null) {
            repository.disable(musicId, version);
        } else {
            repository.save(review, video, Instant.now());
        }
    }

    private static long integer(JsonNode manifest, String field) {
        if (!manifest.path(field).isIntegralNumber() || !manifest.path(field).canConvertToLong()) {
            throw new IllegalArgumentException("integer field required");
        }
        return manifest.path(field).asLong();
    }

    private static String text(JsonNode manifest, String field) {
        if (!manifest.path(field).isString()) {
            throw new IllegalArgumentException("string field required");
        }
        return manifest.path(field).asText();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("catalog environment required");
        }
        return value;
    }
}
