package com.muse.meomuneum.musicrecord.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class MusicRecordRepositoryTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0");

    private static JdbcTemplate jdbc;
    private MusicRecordRepository repository;
    private long userId;
    private long regionId;

    @BeforeAll
    static void migrateSchema() {
        DataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void createScope() {
        repository = new MusicRecordRepository(jdbc);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        userId = nextId();
        long sidoId = nextId();
        regionId = nextId();
        jdbc.update("INSERT INTO users (id,email,password_hash,nickname,role) VALUES (?,?,?,?,?)", userId,
                suffix + "@location.test", "test-only", "tester", "USER");
        jdbc.update("INSERT INTO regions (id,code,name,level,is_active) VALUES (?,?,'Seoul','SIDO',TRUE)", sidoId,
                "s" + suffix);
        jdbc.update("INSERT INTO regions (id,parent_id,code,name,level,is_active) VALUES (?,?,?,'District',"
                + "'SIGUNGU',TRUE)", regionId, sidoId, "g" + suffix);
    }

    @Test
    void activeOwnRecordQueryIsScopedByUserRegionAndSoftDelete() {
        long musicId = nextId();
        long dotId = nextId();
        long recordId = nextId();
        jdbc.update("INSERT INTO music (id,provider,external_music_id,title,artist_name) VALUES (?,?,?,?,?)",
                musicId, "ITUNES", UUID.randomUUID().toString(), "Song", "Artist");
        jdbc.update("INSERT INTO map_dots (id,code,region_id,latitude,longitude,is_active) VALUES (?,?,?,?,?,TRUE)",
                dotId, UUID.randomUUID().toString(), regionId, 37.5, 127.0);
        jdbc.update("INSERT INTO music_records (id,user_id,music_id,map_dot_id,region_id) VALUES (?,?,?,?,?)",
                recordId, userId, musicId, dotId, regionId);

        assertThat(repository.existsActiveRecordInRegion(userId, regionId)).isTrue();
        assertThat(repository.existsActiveRecordInRegion(userId + 1, regionId)).isFalse();
        assertThat(repository.existsActiveRecordInRegion(userId, regionId + 1)).isFalse();

        jdbc.update("UPDATE music_records SET deleted_at=UTC_TIMESTAMP(6) WHERE id=?", recordId);

        assertThat(repository.existsActiveRecordInRegion(userId, regionId)).isFalse();
    }

    private long nextId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }
}
