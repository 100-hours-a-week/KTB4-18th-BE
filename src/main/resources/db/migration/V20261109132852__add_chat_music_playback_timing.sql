-- Pin the reviewed source and duration when a track starts; later catalog edits must not move its deadline.
ALTER TABLE playback_states
    ADD COLUMN source_video_id VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN duration_millis BIGINT NULL,
    ADD COLUMN due_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_playback_source_pair CHECK (
        (source_video_id IS NULL AND duration_millis IS NULL) OR
        (source_video_id IS NOT NULL AND CHAR_LENGTH(source_video_id)=11
          AND duration_millis BETWEEN 1 AND 9999999999)),
    ADD CONSTRAINT ck_playback_due CHECK (due_at IS NULL OR state='PLAYING'),
    ADD INDEX idx_playback_due (state,due_at,chat_room_id);
-- Existing IDLE rows and timestamps remain unchanged. Legacy active rows without a pinned source fail closed.
