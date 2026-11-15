package com.muse.meomuneum.musicrecord.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muse.meomuneum.musicrecord.service.MusicSearchIngestJobService.ClaimedJob;

@Repository
public class MusicSearchIngestDlqRepository {
    private final JdbcTemplate jdbc;

    public MusicSearchIngestDlqRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ClaimedJob job, String errorCode) {
        jdbc.update("INSERT INTO music_search_ingest_dlq (job_id, failure_token, payload_version, provider, "
                + "external_music_id, title, artist_name, album_cover_url, preview_url, youtube_video_id, "
                + "metadata_observed_at, attempt_count, error_code, failed_at) "
                + "SELECT id, lease_token, payload_version, provider, external_music_id, title, artist_name, "
                + "album_cover_url, preview_url, youtube_video_id, metadata_observed_at, attempt_count, ?, "
                + "CURRENT_TIMESTAMP(6) FROM music_search_ingest_jobs WHERE id=?", errorCode, job.id());
    }

    public boolean markReplayed(long jobId) {
        var ids = jdbc.queryForList("SELECT id FROM music_search_ingest_dlq WHERE job_id=? "
                + "AND replayed_at IS NULL ORDER BY id DESC LIMIT 1 FOR UPDATE", Long.class, jobId);
        if (ids.isEmpty()) {
            return false;
        }
        jdbc.update("UPDATE music_search_ingest_dlq SET replayed_at=CURRENT_TIMESTAMP(6) WHERE id=?", ids.getFirst());
        return true;
    }
}
