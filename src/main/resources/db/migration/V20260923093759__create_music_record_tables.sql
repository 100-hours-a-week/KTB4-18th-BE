CREATE TABLE regions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT NULL,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    level VARCHAR(20) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_regions_code UNIQUE (code),
    CONSTRAINT fk_regions_parent FOREIGN KEY (parent_id) REFERENCES regions(id)
);

CREATE TABLE map_dots (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    region_id BIGINT NOT NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_map_dots_code UNIQUE (code),
    CONSTRAINT fk_map_dots_region FOREIGN KEY (region_id) REFERENCES regions(id)
);

CREATE TABLE music_records (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    music_id BIGINT NOT NULL,
    map_dot_id BIGINT NOT NULL,
    region_id BIGINT NOT NULL,
    custom_place_name VARCHAR(100) NULL,
    emotion_memo VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT fk_music_records_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_music_records_music FOREIGN KEY (music_id) REFERENCES music(id),
    CONSTRAINT fk_music_records_map_dot FOREIGN KEY (map_dot_id) REFERENCES map_dots(id),
    CONSTRAINT fk_music_records_region FOREIGN KEY (region_id) REFERENCES regions(id),
    INDEX idx_music_records_user_cursor (user_id, deleted_at, created_at DESC, id DESC)
);
