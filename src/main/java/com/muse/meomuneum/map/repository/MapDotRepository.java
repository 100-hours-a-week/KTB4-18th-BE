package com.muse.meomuneum.map.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MapDotRepository {
    private final JdbcTemplate jdbc;

    public MapDotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 공개된 기록만 후보로 만든 뒤 도트별 최신 기록 하나를 고른다. */
    public List<LatestMapDotRecord> findLatestPublicRecords() {
        String sql = "WITH ranked_records AS ("
                + " SELECT mr.map_dot_id, m.album_cover_url, mr.created_at, mr.id,"
                + " ROW_NUMBER() OVER (PARTITION BY mr.map_dot_id"
                + " ORDER BY mr.created_at DESC, mr.id DESC) AS record_rank"
                + " FROM music_records mr"
                + " JOIN users u ON u.id = mr.user_id"
                + " JOIN user_settings us ON us.user_id = u.id"
                + " JOIN music m ON m.id = mr.music_id"
                + " WHERE mr.deleted_at IS NULL AND u.deleted_at IS NULL"
                + " AND us.map_visibility = 'PUBLIC'"
                + ") SELECT map_dot_id, album_cover_url, created_at"
                + " FROM ranked_records WHERE record_rank = 1";
        return jdbc.query(sql, (row, index) -> {
            LocalDateTime createdAt = row.getObject("created_at", LocalDateTime.class);
            return new LatestMapDotRecord(row.getLong("map_dot_id"), row.getString("album_cover_url"),
                    createdAt.toInstant(ZoneOffset.UTC));
        });
    }

    public record LatestMapDotRecord(long mapDotId, String albumCoverUrl, Instant recordedAt) {
    }
}
