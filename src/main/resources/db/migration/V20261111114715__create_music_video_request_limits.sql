-- Fixed windows are shared by Chat tasks; they are independent of room transactions.
CREATE TABLE music_video_request_limits (
    scope_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    window_start BIGINT NOT NULL,
    request_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (scope_key,window_start),
    CONSTRAINT ck_music_video_request_limit_count CHECK (request_count >= 0),
    INDEX idx_music_video_request_limit_window (window_start)
);
