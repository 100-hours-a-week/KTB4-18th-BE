package com.muse.meomuneum.music.catalog;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared admission predicate for step 4; this class exposes no public API or stored eligibility list. */
@Repository
public class RequestableMusicRepository {
    private final JdbcTemplate jdbc;

    public RequestableMusicRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Sources> findSources(long userId, long regionId, long musicId) {
        return jdbc.query("""
                SELECT m.id,
                  EXISTS(SELECT 1 FROM music_records mr JOIN users owner ON owner.id=mr.user_id
                    LEFT JOIN user_settings settings ON settings.user_id=owner.id
                    WHERE mr.music_id=m.id AND mr.region_id=? AND mr.deleted_at IS NULL
                      AND owner.deleted_at IS NULL AND (owner.id=? OR settings.map_visibility='PUBLIC')) recorded,
                  EXISTS(SELECT 1 FROM recommendation_items i JOIN recommendation_sessions s
                    ON s.id=i.recommendation_session_id WHERE i.music_id=m.id AND s.region_id=?
                      AND s.user_id=? AND s.status='COMPLETED') recommended
                FROM music m JOIN music_playback_sources ps ON ps.music_id=m.id
                JOIN regions g ON g.id=? JOIN regions p ON p.id=g.parent_id
                JOIN users requester ON requester.id=? AND requester.deleted_at IS NULL
                WHERE m.id=? AND BINARY ps.youtube_video_id=BINARY m.youtube_video_id AND ps.status='VERIFIED'
                  AND ps.playback_country='KR' AND ps.verified_at<=UTC_TIMESTAMP(6)
                  AND ps.valid_until>UTC_TIMESTAMP(6) AND g.level='SIGUNGU' AND p.level='SIDO'
                  AND g.is_active=TRUE AND p.is_active=TRUE
                """, (row, index) -> new Sources(row.getLong("id"), row.getBoolean("recorded"),
                row.getBoolean("recommended")), regionId, userId, regionId, userId, regionId, userId, musicId)
                .stream().filter(value -> value.recorded() || value.recommended()).findFirst();
    }

    public record Sources(long musicId, boolean recorded, boolean recommended) {
    }
}
