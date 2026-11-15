package com.muse.meomuneum.musicrecord;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.repository.MusicRecordRepository;
import com.muse.meomuneum.musicrecord.repository.MusicSearchIngestDlqRepository;
import com.muse.meomuneum.musicrecord.repository.MusicSearchIngestJobRepository;
import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService;
import com.muse.meomuneum.musicrecord.service.MusicSearchStorageService;

@Testcontainers
abstract class MusicSearchIngestMySqlSupport {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0")
            .withDatabaseName("music_worker_test").withUsername("test").withPassword("test");
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void schema() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            for (String file : List.of("V1__recommendation_sample.sql",
                    "V20261002133615__expand_music_artist_name_length.sql",
                    "V20261110093705__create_music_search_ingest_jobs.sql",
                    "V20261115190842__add_music_search_storage_recovery.sql",
                    "V20261115204849__align_music_search_ingest_metadata_columns.sql",
                    "V20261115233805__align_music_catalog_compatibility.sql",
                    "V20261115233818__extend_music_search_dlq_payload.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/music-search-legacy/" + file));
            }
        }
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM music_search_ingest_dlq");
        jdbc.update("DELETE FROM music_search_ingest_jobs");
        jdbc.update("DELETE FROM music");
    }

    AnnotationConfigApplicationContext context() {
        return context(3);
    }

    AnnotationConfigApplicationContext context(int maxAttempts) {
        return context(new MusicRecordRepository(jdbc), new MusicSearchIngestDlqRepository(jdbc), maxAttempts);
    }

    AnnotationConfigApplicationContext context(MusicRecordRepository music, MusicSearchIngestDlqRepository dlq) {
        return context(music, dlq, 3);
    }

    private AnnotationConfigApplicationContext context(MusicRecordRepository music,
            MusicSearchIngestDlqRepository dlq, int maxAttempts) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "storage-test", java.util.Map.of("music.search-storage.max-attempts", maxAttempts)));
        context.register(TransactionConfiguration.class);
        context.registerBean("transactionManager", PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(MusicSearchIngestJobRepository.class, () -> new MusicSearchIngestJobRepository(jdbc));
        context.registerBean(MusicSearchIngestDlqRepository.class, () -> dlq);
        context.registerBean(MusicRecordRepository.class, () -> music);
        context.registerBean(MusicSearchIngestJobService.class);
        context.registerBean(MusicSearchStorageService.class);
        context.refresh();
        return context;
    }

    long jobId(String externalId) {
        return jdbc.queryForObject("SELECT id FROM music_search_ingest_jobs WHERE external_music_id=?",
                Long.class, externalId);
    }

    String status(String externalId) {
        return jdbc.queryForObject("SELECT status FROM music_search_ingest_jobs WHERE external_music_id=?",
                String.class, externalId);
    }

    void expire(long id) {
        jdbc.update("UPDATE music_search_ingest_jobs SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), "
                + "INTERVAL 1 SECOND) WHERE id=?", id);
    }

    MusicItem item(String id, String title) {
        return new MusicItem(null, "ITUNES", id, title, "artist", null, null, null, false);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }
}
