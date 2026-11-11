package com.muse.meomuneum.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class MusicVideoStorageMySqlTest {
    private static final String VERSION = "20261111104317";
    private static final String VIDEO = "abcdefghijk";
    private static final String OLD_VIDEO = "lmnopqrstuv";
    private static final long USER = 990_100_001;
    private static final long REGION = 990_100_002;
    private static final long ROOM = 990_100_003;
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7.0").withCommand("--skip-log-bin");
    private static JdbcTemplate jdbc;
    private static long music;
    private static long unreviewed;

    @BeforeAll
    static void upgrade() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("20261110193555").load().migrate();
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname,role) VALUES (?,?,'test','test','USER')",
                USER, USER + "@video.test");
        jdbc.update("INSERT INTO regions(id,code,name,level) VALUES (?,'video-test-region','test','SIGUNGU')", REGION);
        jdbc.update("INSERT INTO chat_rooms(id,region_id) VALUES (?,?)", ROOM, REGION);
        music = song("reviewed", VIDEO);
        unreviewed = song("unreviewed", null);
        jdbc.update("""
                INSERT INTO music_playback_sources(music_id,youtube_video_id,channel_id,channel_kind,
                  review_reference,reviewed_by,status,duration_millis,playback_country,verified_at,valid_until,version)
                VALUES (?,?,'synthetic','TOPIC','test-only','test','VERIFIED',120000,'KR',
                  UTC_TIMESTAMP(6),TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)),1)
                """, music, VIDEO);
        queue(1, music, "PLAYING");
        queue(2, music, "WAITING");
        queue(3, unreviewed, "WAITING");
        long item = jdbc.queryForObject("SELECT id FROM music_queue_items WHERE room_id=? AND sequence_no=1",
                Long.class, ROOM);
        jdbc.update("""
                INSERT INTO playback_states(chat_room_id,current_queue_item_id,state,position_seconds,
                  version,next_sequence_no,source_video_id,duration_millis)
                VALUES (?,?,'PAUSED',12.345,7,4,?,99000)
                """, ROOM, item, OLD_VIDEO);
        var migration = Flyway.configure().dataSource(dataSource).target(VERSION).load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        migration.validate();
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }

    @Test
    void upgradePreservesCurrentPinReviewedDataAndUnresolvedWaitingItem() {
        assertThat(jdbc.queryForMap("SELECT state,version,next_sequence_no,source_video_id,duration_millis "
                + "FROM playback_states WHERE chat_room_id=?", ROOM))
                .containsEntry("state", "PAUSED").containsEntry("version", 7L)
                .containsEntry("next_sequence_no", 4L).containsEntry("source_video_id", OLD_VIDEO)
                .containsEntry("duration_millis", 99000L);
        assertThat(jdbc.queryForMap("SELECT source_video_id,duration_millis FROM music_queue_items "
                + "WHERE room_id=? AND sequence_no=1", ROOM))
                .containsEntry("source_video_id", OLD_VIDEO).containsEntry("duration_millis", 99000L);
        assertThat(jdbc.queryForMap("SELECT source_video_id,duration_millis FROM music_queue_items "
                + "WHERE room_id=? AND sequence_no=2", ROOM))
                .containsEntry("source_video_id", VIDEO).containsEntry("duration_millis", 120000L);
        assertThat(jdbc.queryForMap("SELECT source_video_id,duration_millis FROM music_queue_items "
                + "WHERE room_id=? AND sequence_no=3", ROOM))
                .containsEntry("source_video_id", null).containsEntry("duration_millis", null);
        assertThat(jdbc.queryForMap("SELECT playback_status,official_status FROM music_video_sources "
                + "WHERE music_id=? AND youtube_video_id=?", music, OLD_VIDEO))
                .containsEntry("playback_status", "UNKNOWN").containsEntry("official_status", "UNREVIEWED");
        assertThat(jdbc.queryForMap("SELECT official_status,review_reference FROM music_video_sources "
                + "WHERE music_id=? AND youtube_video_id=?", music, VIDEO))
                .containsEntry("official_status", "CONFIRMED").containsEntry("review_reference", "test-only");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_playback_sources WHERE music_id=?",
                Long.class, music)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT youtube_video_id FROM music WHERE id=?", String.class, music))
                .isEqualTo(VIDEO);
    }

    @Test
    void constraintsSeparatePlayabilityFromOfficialReviewAndAllowMultipleSources() {
        assertThatThrownBy(() -> jdbc.update("UPDATE music_video_sources SET playback_status='PLAYABLE' "
                + "WHERE music_id=? AND youtube_video_id=?", music, OLD_VIDEO))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_music_video_source_playable");
        assertThatThrownBy(() -> jdbc.update("UPDATE music_video_sources SET official_status='CONFIRMED' "
                + "WHERE music_id=? AND youtube_video_id=?", music, OLD_VIDEO))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_music_video_source_review");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM music_video_sources WHERE music_id=?",
                Long.class, music)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE music_queue_items SET source_video_id=?,duration_millis=NULL "
                + "WHERE room_id=? AND sequence_no=3", VIDEO, ROOM))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_music_queue_selected_source");
        assertThatThrownBy(() -> jdbc.update("UPDATE music_queue_items SET source_video_id=?,duration_millis=120000 "
                + "WHERE room_id=? AND sequence_no=3", VIDEO, ROOM))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_music_queue_selected_source");
    }

    @Test
    void candidateCannotCrossMusicBoundaryDuplicateRankOrInventAsyncJobState() {
        String batch = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO music_video_search_results
                (id,music_id,metadata_fingerprint,policy_version,result_kind,expires_at)
                VALUES (?,?,?,1,'READY',TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)))
                """, batch, music, "a".repeat(64));
        String sql = """
                INSERT INTO music_video_candidates
                (id,search_id,music_id,youtube_video_id,rank_no,title,channel_title,duration_millis)
                VALUES (?,?,?,?,1,'synthetic','synthetic',120000)
                """;
        jdbc.update(sql, UUID.randomUUID().toString(), batch, music, VIDEO);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID().toString(), batch, unreviewed, VIDEO))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("foreign key constraint fails");
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID().toString(), batch, music, OLD_VIDEO))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_music_video_candidate_rank");
        assertThatThrownBy(
                () -> jdbc.update("UPDATE music_video_search_results SET result_kind='PROCESSING' WHERE id=?",
                        batch))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_music_video_search_kind");
        assertThatThrownBy(() -> jdbc.update("UPDATE music_video_search_results SET expires_at=created_at WHERE id=?",
                batch)).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_music_video_search_expiry");
    }

    private static long song(String external, String video) {
        jdbc.update("INSERT INTO music(provider,external_music_id,title,artist_name,youtube_video_id) "
                + "VALUES ('ITUNES',?,'synthetic','synthetic',?)", external, video);
        return jdbc.queryForObject("SELECT id FROM music WHERE external_music_id=?", Long.class, external);
    }

    private static void queue(long sequence, long song, String status) {
        jdbc.update("INSERT INTO music_queue_items(room_id,music_id,added_by_user_id,sequence_no,status,started_at) "
                + "VALUES (?,?,?,?,?,IF(?='PLAYING',UTC_TIMESTAMP(6),NULL))", ROOM, song, USER, sequence, status,
                status);
    }
}
