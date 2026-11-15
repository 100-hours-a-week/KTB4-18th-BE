package com.muse.meomuneum.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class MusicVideoCandidateLimitMySqlTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0").withCommand("--skip-log-bin");

    @Test
    void widensOnlyCandidateRankAndPreservesExistingRowsOnUpgradeAndRerun() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("20261111131732").load().migrate();
        String external = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO music(provider,external_music_id,title,artist_name) VALUES ('ITUNES',?,'Song','Artist')",
                external);
        long music = jdbc.queryForObject("SELECT id FROM music WHERE external_music_id=?", Long.class, external);
        for (String video : java.util.List.of("ABCDEFGHIJK", "LMNOPQRSTUV", "ZYXWVUTSRQP")) {
            jdbc.update("INSERT INTO music_video_sources(music_id,youtube_video_id) VALUES (?,?)", music, video);
        }
        String search = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO music_video_search_results(id,music_id,metadata_fingerprint,policy_version,
                result_kind,expires_at)
                VALUES (?,?,?,1,'READY',TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)))
                """, search, music, "a".repeat(64));
        insert(jdbc, search, music, "ABCDEFGHIJK", 10);
        assertThatThrownBy(() -> insert(jdbc, search, music, "LMNOPQRSTUV", 11))
                .hasMessageContaining("ck_music_video_candidate_numbers");
        var before = jdbc.queryForList("SELECT * FROM music_video_candidates WHERE music_id=?", music);
        var flyway = Flyway.configure().dataSource(source).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM music_video_candidates WHERE music_id=?", music)).isEqualTo(before);
        insert(jdbc, search, music, "LMNOPQRSTUV", 50);
        assertThatThrownBy(() -> insert(jdbc, search, music, "ZYXWVUTSRQP", 51))
                .hasMessageContaining("ck_music_video_candidate_numbers");
    }

    private void insert(JdbcTemplate jdbc, String search, long music, String video, int rank) {
        jdbc.update("""
                INSERT INTO music_video_candidates(id,search_id,music_id,youtube_video_id,rank_no,title,
                channel_title,duration_millis)
                VALUES (?,?,?,?,?,'Video','Channel',120000)
                """, UUID.randomUUID().toString(), search, music, video, rank);
    }
}
