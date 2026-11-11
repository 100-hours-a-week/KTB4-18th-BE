-- Multi-video metadata is independent of the legacy one-reviewed-source-per-song table.
CREATE TABLE music_video_sources (
    music_id BIGINT NOT NULL,
    youtube_video_id VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    channel_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    playback_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    official_status VARCHAR(16) NOT NULL DEFAULT 'UNREVIEWED',
    duration_millis BIGINT NULL,
    playback_country CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'KR',
    verified_at DATETIME(6) NULL,
    valid_until DATETIME(6) NULL,
    review_reference VARCHAR(1000) NULL,
    reviewed_by VARCHAR(100) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (music_id,youtube_video_id),
    CONSTRAINT fk_music_video_source_music FOREIGN KEY (music_id) REFERENCES music(id),
    CONSTRAINT ck_music_video_source_id CHECK (youtube_video_id REGEXP '^[A-Za-z0-9_-]{11}$'),
    CONSTRAINT ck_music_video_source_status CHECK (playback_status IN ('UNKNOWN','PLAYABLE','UNAVAILABLE')
        AND official_status IN ('UNREVIEWED','CONFIRMED')),
    CONSTRAINT ck_music_video_source_duration CHECK (duration_millis IS NULL
        OR duration_millis BETWEEN 1 AND 9999999999),
    CONSTRAINT ck_music_video_source_period CHECK (
        (verified_at IS NULL AND valid_until IS NULL) OR
        (verified_at IS NOT NULL AND valid_until IS NOT NULL AND valid_until>verified_at)),
    CONSTRAINT ck_music_video_source_playable CHECK (playback_status<>'PLAYABLE' OR
        (duration_millis IS NOT NULL AND verified_at IS NOT NULL AND valid_until IS NOT NULL
          AND channel_id IS NOT NULL AND CHAR_LENGTH(channel_id)>0 AND playback_country='KR')),
    CONSTRAINT ck_music_video_source_review CHECK (official_status<>'CONFIRMED' OR
        (review_reference IS NOT NULL AND CHAR_LENGTH(review_reference)>0
          AND reviewed_by IS NOT NULL AND CHAR_LENGTH(reviewed_by)>0)),
    CONSTRAINT ck_music_video_source_version CHECK (version BETWEEN 1 AND 9007199254740991),
    INDEX idx_music_video_source_expiry (valid_until,music_id)
);

INSERT INTO music_video_sources
    (music_id,youtube_video_id,channel_id,playback_status,official_status,duration_millis,
     playback_country,verified_at,valid_until,review_reference,reviewed_by,version)
SELECT music_id,youtube_video_id,channel_id,
    CASE WHEN status='VERIFIED' THEN 'PLAYABLE' ELSE 'UNAVAILABLE' END,
    'CONFIRMED',duration_millis,playback_country,verified_at,valid_until,review_reference,reviewed_by,version
FROM music_playback_sources;

-- An active pin can outlive a later change of the legacy representative source. Keep its identity,
-- but do not invent a fresh verification or official review for a source absent from the review table.
INSERT INTO music_video_sources (music_id,youtube_video_id,duration_millis)
SELECT q.music_id,p.source_video_id,MIN(p.duration_millis)
FROM playback_states p JOIN music_queue_items q ON q.id=p.current_queue_item_id AND q.room_id=p.chat_room_id
WHERE p.source_video_id IS NOT NULL AND p.duration_millis IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM music_video_sources s
        WHERE s.music_id=q.music_id AND s.youtube_video_id=p.source_video_id)
GROUP BY q.music_id,p.source_video_id;

-- Immutable completed cache batches, not asynchronous jobs. Concurrent requests may create distinct batches.
CREATE TABLE music_video_search_results (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    music_id BIGINT NOT NULL,
    metadata_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    playback_country CHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'KR',
    policy_version INT NOT NULL,
    result_kind VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_music_video_search_music UNIQUE (id,music_id),
    CONSTRAINT fk_music_video_search_music FOREIGN KEY (music_id) REFERENCES music(id),
    CONSTRAINT ck_music_video_search_id CHECK
        (id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_music_video_search_metadata CHECK (metadata_fingerprint REGEXP '^[0-9a-f]{64}$'
        AND policy_version>0 AND playback_country='KR'),
    CONSTRAINT ck_music_video_search_kind CHECK (result_kind IN ('READY','EMPTY')),
    CONSTRAINT ck_music_video_search_expiry CHECK (expires_at>created_at),
    INDEX idx_music_video_search_cache (music_id,metadata_fingerprint,playback_country,policy_version,expires_at),
    INDEX idx_music_video_search_expiry (expires_at,id)
);

CREATE TABLE music_video_candidates (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    search_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    music_id BIGINT NOT NULL,
    youtube_video_id VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rank_no SMALLINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    channel_title VARCHAR(255) NOT NULL,
    thumbnail_url VARCHAR(1000) NULL,
    duration_millis BIGINT NOT NULL,
    CONSTRAINT fk_music_video_candidate_search FOREIGN KEY (search_id,music_id)
        REFERENCES music_video_search_results(id,music_id),
    CONSTRAINT fk_music_video_candidate_source FOREIGN KEY (music_id,youtube_video_id)
        REFERENCES music_video_sources(music_id,youtube_video_id),
    CONSTRAINT uk_music_video_candidate_rank UNIQUE (search_id,rank_no),
    CONSTRAINT uk_music_video_candidate_video UNIQUE (search_id,youtube_video_id),
    CONSTRAINT ck_music_video_candidate_id CHECK
        (id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_music_video_candidate_numbers CHECK (rank_no BETWEEN 1 AND 10
        AND duration_millis BETWEEN 1 AND 9999999999),
    CONSTRAINT ck_music_video_candidate_text CHECK (CHAR_LENGTH(title)>0 AND CHAR_LENGTH(channel_title)>0)
);

ALTER TABLE music_queue_items
    ADD COLUMN source_video_id VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN duration_millis BIGINT NULL,
    ADD CONSTRAINT fk_music_queue_selected_source FOREIGN KEY (music_id,source_video_id)
        REFERENCES music_video_sources(music_id,youtube_video_id),
    ADD CONSTRAINT ck_music_queue_selected_source CHECK (
        (source_video_id IS NULL AND duration_millis IS NULL) OR
        (source_video_id IS NOT NULL AND source_video_id REGEXP '^[A-Za-z0-9_-]{11}$'
          AND duration_millis IS NOT NULL AND duration_millis BETWEEN 1 AND 9999999999));

-- Preserve the exact current playback pin, not the representative source as of migration time.
UPDATE music_queue_items q JOIN playback_states p ON p.current_queue_item_id=q.id AND p.chat_room_id=q.room_id
SET q.source_video_id=p.source_video_id,q.duration_millis=p.duration_millis
WHERE q.status='PLAYING' AND p.source_video_id IS NOT NULL AND p.duration_millis IS NOT NULL;

-- Only an existing matching, unexpired reviewed source may be used for a legacy waiting item.
UPDATE music_queue_items q JOIN music m ON m.id=q.music_id
JOIN music_video_sources s ON s.music_id=q.music_id AND BINARY s.youtube_video_id=BINARY m.youtube_video_id
SET q.source_video_id=s.youtube_video_id,q.duration_millis=s.duration_millis
WHERE q.status='WAITING' AND s.playback_status='PLAYABLE' AND s.playback_country='KR'
    AND s.verified_at<=UTC_TIMESTAMP(6) AND s.valid_until>UTC_TIMESTAMP(6);
