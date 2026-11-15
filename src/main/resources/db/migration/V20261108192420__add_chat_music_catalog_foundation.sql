-- Nullable provenance preserves legacy recommendations without inventing their region.
ALTER TABLE recommendation_sessions
    ADD COLUMN location_request_id CHAR(36) NULL,
    ADD CONSTRAINT uk_recommendation_location_request UNIQUE (user_id, location_request_id),
    ADD INDEX idx_recommendation_region_source (user_id, region_id, status, id);

CREATE INDEX idx_music_records_region_source ON music_records (region_id, music_id, deleted_at, user_id);

-- Review metadata only: this is not a stored list of requestable songs.
CREATE TABLE music_playback_sources (
    music_id BIGINT NOT NULL PRIMARY KEY,
    youtube_video_id VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel_kind VARCHAR(20) NOT NULL,
    review_reference VARCHAR(1000) NOT NULL,
    reviewed_by VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    duration_millis BIGINT NOT NULL,
    playback_country CHAR(2) NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_music_playback_source_music FOREIGN KEY (music_id) REFERENCES music(id),
    CONSTRAINT chk_music_source_kind CHECK (channel_kind IN ('ARTIST','DISTRIBUTOR','TOPIC')),
    CONSTRAINT chk_music_source_status CHECK (status IN ('VERIFIED','UNAVAILABLE')),
    CONSTRAINT chk_music_source_duration CHECK (duration_millis > 0),
    CONSTRAINT chk_music_source_period CHECK (valid_until > verified_at),
    CONSTRAINT chk_music_source_version CHECK (version > 0)
);
