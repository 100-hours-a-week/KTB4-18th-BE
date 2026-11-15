ALTER TABLE music
    ADD COLUMN metadata_observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN metadata_version BIGINT NOT NULL DEFAULT 0;
UPDATE music SET metadata_observed_at=COALESCE(updated_at, created_at);

ALTER TABLE music_search_ingest_jobs
    ADD COLUMN metadata_observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN payload_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN attempt_started_at DATETIME(6) NULL;
UPDATE music_search_ingest_jobs SET metadata_observed_at=created_at,
    attempt_started_at=IF(status='PROCESSING', updated_at, NULL);

CREATE TABLE music_search_ingest_dlq (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    failure_token VARCHAR(64) NOT NULL,
    payload_version BIGINT NOT NULL,
    provider VARCHAR(30) NOT NULL,
    external_music_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    artist_name VARCHAR(1000) NOT NULL,
    album_cover_url VARCHAR(1000) NULL,
    preview_url VARCHAR(1000) NULL,
    youtube_video_id VARCHAR(32) NULL,
    metadata_observed_at DATETIME(6) NOT NULL,
    attempt_count INT NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    failed_at DATETIME(6) NOT NULL,
    replayed_at DATETIME(6) NULL,
    CONSTRAINT uq_music_search_ingest_dlq_failure UNIQUE (job_id, failure_token),
    INDEX idx_music_search_ingest_dlq_replay (job_id, replayed_at, id)
) ENGINE=InnoDB;

INSERT INTO music_search_ingest_dlq (job_id, failure_token, payload_version, provider,
    external_music_id, title, artist_name, album_cover_url, preview_url, youtube_video_id,
    metadata_observed_at, attempt_count, error_code, failed_at)
SELECT id, CONCAT('legacy-', id), payload_version, provider, external_music_id, title,
    artist_name, album_cover_url, preview_url, youtube_video_id, metadata_observed_at,
    attempt_count, COALESCE(last_error_code, 'legacy_failed'), updated_at
FROM music_search_ingest_jobs WHERE status='FAILED';
UPDATE music_search_ingest_jobs SET status='DEAD', lease_token=NULL, lease_until=NULL,
    attempt_started_at=NULL WHERE status='FAILED';
