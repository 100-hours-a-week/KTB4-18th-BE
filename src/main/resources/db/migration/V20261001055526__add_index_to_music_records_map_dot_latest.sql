CREATE INDEX idx_music_records_map_dot_latest
    ON music_records (map_dot_id, deleted_at, created_at DESC, id DESC);
