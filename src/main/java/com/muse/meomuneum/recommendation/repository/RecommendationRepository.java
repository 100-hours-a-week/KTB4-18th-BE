package com.muse.meomuneum.recommendation.repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import com.muse.meomuneum.recommendation.dto.request.RecommendationRequest;
import com.muse.meomuneum.recommendation.dto.response.RecommendationResponse;
import com.muse.meomuneum.recommendation.dto.TrackData;
import com.muse.meomuneum.recommendation.exception.RecommendationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** SQL을 모아 둔 클래스입니다. 모든 쿼리는 파라미터 바인딩을 사용합니다. */
@Repository
public class RecommendationRepository {
    private final JdbcTemplate jdbc;

    public RecommendationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long createSession(RecommendationRequest request, String guestSessionId, Long userId, Instant now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO recommendation_sessions
                    (user_id, guest_session_id, trigger_type, input_type, conversation_key, status, created_at)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?)
                    """, new String[]{"id"});
            statement.setObject(1, userId);
            statement.setString(2, guestSessionId);
            statement.setString(3, request.trigger_type());
            statement.setString(4, request.input_type());
            statement.setString(5, request.conversation_key());
            statement.setTimestamp(6, Timestamp.from(now));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    public long saveMusic(TrackData track) {
        // 같은 곡은 중복 저장하지 않습니다. UNIQUE(provider, external_music_id)와 함께 사용합니다.
        jdbc.update("""
                INSERT INTO music (provider, external_music_id, title, artist_name, album_cover_url, preview_url)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE title = VALUES(title), artist_name = VALUES(artist_name),
                    album_cover_url = VALUES(album_cover_url), preview_url = VALUES(preview_url)
                """, track.provider(), track.externalId(), track.title(), track.artist(), track.coverUrl(), track.previewUrl());
        return jdbc.queryForObject("SELECT id FROM music WHERE provider = ? AND external_music_id = ?",
                Long.class, track.provider(), track.externalId());
    }

    public void saveItem(long sessionId, long musicId, int rank) {
        jdbc.update("INSERT INTO recommendation_items (recommendation_session_id, music_id, rank_no) VALUES (?, ?, ?)",
                sessionId, musicId, rank);
    }

    public void complete(long sessionId, Instant completedAt) {
        jdbc.update("UPDATE recommendation_sessions SET status = 'COMPLETED', completed_at = ? WHERE id = ?",
                Timestamp.from(completedAt), sessionId);
    }

    public SavedSession findSession(long id) {
        return jdbc.query("""
                SELECT id, user_id, guest_session_id, status, conversation_key, completed_at
                FROM recommendation_sessions WHERE id = ?
                """, (row, index) -> new SavedSession(row.getLong("id"), row.getObject("user_id", Long.class),
                row.getString("guest_session_id"), row.getString("status"), row.getString("conversation_key"),
                row.getTimestamp("completed_at") == null ? null : row.getTimestamp("completed_at").toInstant()), id)
                .stream().findFirst().orElseThrow(() -> new RecommendationException(404, "추천 결과를 찾을 수 없습니다."));
    }

    public List<RecommendationResponse.Item> findItems(long id) {
        return jdbc.query("""
                SELECT i.rank_no, m.id, m.title, m.artist_name, m.album_cover_url, m.preview_url
                FROM recommendation_items i JOIN music m ON m.id = i.music_id
                WHERE i.recommendation_session_id = ? ORDER BY i.rank_no
                """, (row, index) -> new RecommendationResponse.Item(row.getInt("rank_no"),
                new RecommendationResponse.Music(row.getLong("id"), row.getString("title"),
                        row.getString("artist_name"), row.getString("album_cover_url"), row.getString("preview_url"))), id);
    }

    public record SavedSession(long id, Long userId, String guestSessionId, String status,
                               String conversationKey, Instant completedAt) {}
}
