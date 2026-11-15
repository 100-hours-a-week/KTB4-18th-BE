package com.muse.meomuneum.musicrecord.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.StorefrontNames;

@Repository
public class MusicSearchIngestJobRepository {
    private static final String PAYLOAD = "provider, external_music_id, title, artist_name, album_cover_url, "
            + "preview_url, youtube_video_id, title_us, artist_name_us, title_ko, artist_name_ko, release_epoch_second";
    private static final String REQUEUE = """
            music_search_ingest_jobs.status='DONE' AND (
                (music_search_ingest_jobs.title_us IS NULL AND incoming.title_us IS NOT NULL)
                OR (music_search_ingest_jobs.artist_name_us IS NULL AND incoming.artist_name_us IS NOT NULL)
                OR (music_search_ingest_jobs.title_ko IS NULL AND incoming.title_ko IS NOT NULL)
                OR (music_search_ingest_jobs.artist_name_ko IS NULL AND incoming.artist_name_ko IS NOT NULL)
                OR (music_search_ingest_jobs.release_epoch_second IS NULL AND incoming.release_epoch_second IS NOT NULL)
            )
            """;
    private static final String INSERT = ("INSERT INTO music_search_ingest_jobs (" + PAYLOAD + """
            , status, attempt_count, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
            'QUEUED', 0, CURRENT_TIMESTAMP(6)) AS incoming ON DUPLICATE KEY UPDATE
            attempt_count=IF(%s,0,music_search_ingest_jobs.attempt_count),
            next_attempt_at=IF(%s,CURRENT_TIMESTAMP(6),music_search_ingest_jobs.next_attempt_at),
            status=IF(%s,'QUEUED',music_search_ingest_jobs.status),
            title_us=COALESCE(music_search_ingest_jobs.title_us,incoming.title_us),
            artist_name_us=COALESCE(music_search_ingest_jobs.artist_name_us,incoming.artist_name_us),
            title_ko=COALESCE(music_search_ingest_jobs.title_ko,incoming.title_ko),
            artist_name_ko=COALESCE(music_search_ingest_jobs.artist_name_ko,incoming.artist_name_ko),
            release_epoch_second=COALESCE(music_search_ingest_jobs.release_epoch_second,incoming.release_epoch_second)
            """).formatted(REQUEUE, REQUEUE, REQUEUE);
    private static final String CLAIMABLE = "SELECT id, " + PAYLOAD
            + ", attempt_count FROM music_search_ingest_jobs "
            + "WHERE (status='QUEUED' AND next_attempt_at<=CURRENT_TIMESTAMP(6)) "
            + "OR (status='PROCESSING' AND attempt_count<3 AND lease_until<=CURRENT_TIMESTAMP(6)) "
            + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED";

    private final JdbcTemplate jdbc;

    public MusicSearchIngestJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void admit(List<MusicItem> items) {
        jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                MusicItem item = items.get(index);
                statement.setString(1, item.provider());
                statement.setString(2, item.external_music_id());
                statement.setString(3, item.title());
                statement.setString(4, item.artist_name());
                statement.setString(5, item.album_cover_url());
                statement.setString(6, item.preview_url());
                statement.setString(7, item.youtube_video_id());
                var names = item.storefrontNames();
                statement.setString(8, names.titleUs());
                statement.setString(9, names.artistNameUs());
                statement.setString(10, names.titleKo());
                statement.setString(11, names.artistNameKo());
                statement.setObject(12, item.releaseEpochSecond(), java.sql.Types.BIGINT);
            }

            @Override
            public int getBatchSize() {
                return items.size();
            }
        });
    }

    public void failExhaustedLeases() {
        jdbc.update("UPDATE music_search_ingest_jobs SET status='FAILED', lease_token=NULL, lease_until=NULL, "
                + "last_error_code='worker_lease_exhausted', updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE status='PROCESSING' AND attempt_count>=3 AND lease_until<=CURRENT_TIMESTAMP(6)");
    }

    public List<ClaimCandidate> findClaimable(int limit) {
        return jdbc.query(CLAIMABLE, (row, index) -> new ClaimCandidate(row.getLong("id"),
                item(row), row.getInt("attempt_count")), limit);
    }

    public int claim(long id, String token, int leaseSeconds) {
        return jdbc.update("UPDATE music_search_ingest_jobs SET status='PROCESSING', lease_token=?, "
                + "lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? SECOND), "
                + "attempt_count=attempt_count+1, updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND "
                + "((status='QUEUED' AND next_attempt_at<=CURRENT_TIMESTAMP(6)) OR "
                + "(status='PROCESSING' AND attempt_count<3 AND lease_until<=CURRENT_TIMESTAMP(6)))",
                token, leaseSeconds, id);
    }

    public Optional<MusicItem> findClaimedPayload(long id, String token) {
        return jdbc.query("SELECT " + PAYLOAD + " FROM music_search_ingest_jobs "
                + "WHERE id=? AND status='PROCESSING' AND lease_token=? FOR UPDATE",
                (row, index) -> item(row), id, token).stream().findFirst();
    }

    public int markDone(long id, String token) {
        return jdbc.update("UPDATE music_search_ingest_jobs SET status='DONE', lease_token=NULL, lease_until=NULL, "
                + "last_error_code=NULL, updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=? AND status='PROCESSING' AND lease_token=?", id, token);
    }

    public int markFailure(long id, String token, int attemptCount, boolean retryable, String errorCode) {
        if (retryable && attemptCount < 3) {
            int delaySeconds = attemptCount == 1 ? 5 : 30;
            return jdbc.update("UPDATE music_search_ingest_jobs SET status='QUEUED', lease_token=NULL, "
                    + "lease_until=NULL, next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? SECOND), "
                    + "last_error_code=?, updated_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE id=? AND status='PROCESSING' AND lease_token=?", delaySeconds, errorCode, id, token);
        }
        return jdbc.update("UPDATE music_search_ingest_jobs SET status='FAILED', lease_token=NULL, lease_until=NULL, "
                + "last_error_code=?, updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=? AND status='PROCESSING' AND lease_token=?", errorCode, id, token);
    }

    public int release(long id, String token, int attemptCount) {
        if (attemptCount < 3) {
            return jdbc.update("UPDATE music_search_ingest_jobs SET status='QUEUED', lease_token=NULL, "
                    + "lease_until=NULL, next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 SECOND), "
                    + "last_error_code='worker_dispatch_failed', updated_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE id=? AND status='PROCESSING' AND lease_token=?", id, token);
        }
        return jdbc.update("UPDATE music_search_ingest_jobs SET status='FAILED', lease_token=NULL, lease_until=NULL, "
                + "last_error_code='worker_dispatch_failed', updated_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=? AND status='PROCESSING' AND lease_token=?", id, token);
    }

    public int requeue(long id) {
        return jdbc.update("UPDATE music_search_ingest_jobs SET status='QUEUED', attempt_count=0, "
                + "next_attempt_at=CURRENT_TIMESTAMP(6), lease_token=NULL, lease_until=NULL, last_error_code=NULL, "
                + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='FAILED'", id);
    }

    public boolean exists(long id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM music_search_ingest_jobs WHERE id=?",
                Integer.class, id);
        return count != null && count > 0;
    }

    private MusicItem item(java.sql.ResultSet row) throws SQLException {
        String youtubeVideoId = row.getString("youtube_video_id");
        return new MusicItem(null, row.getString("provider"), row.getString("external_music_id"),
                row.getString("title"), row.getString("artist_name"), row.getString("album_cover_url"),
                row.getString("preview_url"), youtubeVideoId,
                false, new StorefrontNames(row.getString("title_us"), row.getString("artist_name_us"),
                        row.getString("title_ko"), row.getString("artist_name_ko"), false),
                row.getObject("release_epoch_second", Long.class));
    }

    public record ClaimCandidate(long id, MusicItem music, int attemptCount) {
    }
}
