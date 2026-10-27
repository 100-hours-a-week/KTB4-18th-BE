ALTER TABLE chat_room_runtime ADD COLUMN boundary_initialized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_view_intervals
    ADD COLUMN environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN epoch BIGINT NULL,
    ADD COLUMN owner_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN owner_generation BIGINT NULL,
    ADD COLUMN start_after_room_seq BIGINT NULL,
    ADD COLUMN verified_through_room_seq BIGINT NULL,
    ADD COLUMN end_room_seq BIGINT NULL;

-- No message bodies or credentials; historical ids are mapped once under the room lock after a stopped cutover.
CREATE TABLE chat_message_order (
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    epoch BIGINT NOT NULL,
    room_seq BIGINT NOT NULL,
    PRIMARY KEY (environment, message_id),
    UNIQUE KEY uk_chat_message_room_seq (environment, room_id, room_seq),
    FOREIGN KEY (message_id) REFERENCES chat_room_messages(id) ON DELETE CASCADE,
    FOREIGN KEY (environment) REFERENCES chat_runtime_control(environment),
    CHECK (room_seq > 0 AND room_seq <= 9007199254740991)
);
CREATE TABLE chat_query_snapshots (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    epoch BIGINT NOT NULL,
    owner_generation BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    membership_id BIGINT NOT NULL,
    view_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mode VARCHAR(8) NOT NULL,
    snapshot_message_id BIGINT NOT NULL,
    room_seq BIGINT NOT NULL,
    as_of DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_chat_query_expiry (expires_at),
    CHECK (mode IN ('history','recovery'))
);
CREATE TABLE chat_query_snapshot_ranges (
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    interval_id BIGINT NOT NULL,
    start_message_id BIGINT NOT NULL,
    end_message_id BIGINT NULL,
    start_room_seq BIGINT NOT NULL,
    end_room_seq BIGINT NULL,
    started_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, interval_id),
    FOREIGN KEY (snapshot_id) REFERENCES chat_query_snapshots(id) ON DELETE CASCADE
);
