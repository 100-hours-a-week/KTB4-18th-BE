-- 텍스트 추천 개발 범위만 생성합니다. 팀의 전체 초기 마이그레이션과 추후 통합하세요.
CREATE TABLE music (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(30) NOT NULL,
    external_music_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    artist_name VARCHAR(100) NOT NULL,
    album_cover_url VARCHAR(1000) NULL,
    preview_url VARCHAR(1000) NULL,
    youtube_video_id VARCHAR(32) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NULL,
    CONSTRAINT uq_music_provider_external UNIQUE (provider, external_music_id)
);

CREATE TABLE recommendation_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    guest_session_id VARCHAR(128) NULL,
    map_dot_id BIGINT NULL,
    region_id BIGINT NULL,
    trigger_type VARCHAR(30) NOT NULL,
    input_type VARCHAR(20) NOT NULL,
    conversation_key VARCHAR(36) NOT NULL,
    prompt TEXT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL
);

CREATE TABLE recommendation_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recommendation_session_id BIGINT NOT NULL,
    music_id BIGINT NOT NULL,
    rank_no SMALLINT NOT NULL,
    CONSTRAINT fk_items_session FOREIGN KEY (recommendation_session_id) REFERENCES recommendation_sessions(id),
    CONSTRAINT fk_items_music FOREIGN KEY (music_id) REFERENCES music(id),
    CONSTRAINT uq_items_rank UNIQUE (recommendation_session_id, rank_no),
    CONSTRAINT uq_items_music UNIQUE (recommendation_session_id, music_id)
);
