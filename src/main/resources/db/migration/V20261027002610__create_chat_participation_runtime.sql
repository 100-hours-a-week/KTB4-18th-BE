-- Additive, empty runtime state: historical memberships are not online seats.
CREATE TABLE chat_runtime_control (
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 1,
    mode VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    PRIMARY KEY (environment),
    CHECK (epoch > 0),
    CHECK (mode IN ('RUNNING', 'RECOVERING'))
);
CREATE TABLE chat_runtime_tasks (
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    epoch BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    lease_until DATETIME(6) NOT NULL,
    PRIMARY KEY (environment, task_id),
    FOREIGN KEY (environment) REFERENCES chat_runtime_control(environment),
    CHECK (state IN ('ACTIVE', 'DRAINING', 'FENCED')),
    INDEX idx_chat_task_lease (environment, epoch, state, lease_until)
);
CREATE TABLE chat_room_runtime (
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    room_id BIGINT NOT NULL,
    room_seq BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (environment, room_id),
    FOREIGN KEY (environment) REFERENCES chat_runtime_control(environment),
    FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    CHECK (room_seq >= 0 AND room_seq <= 9007199254740991)
);
CREATE TABLE chat_view_runtime (
    view_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    epoch BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    membership_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    deadline DATETIME(6) NULL,
    owner_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    owner_generation BIGINT NOT NULL DEFAULT 0,
    socket_token VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    changed_seq BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (view_id),
    FOREIGN KEY (view_id) REFERENCES chat_view_sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (environment) REFERENCES chat_runtime_control(environment),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    FOREIGN KEY (membership_id) REFERENCES chat_room_members(id),
    FOREIGN KEY (environment, owner_task_id) REFERENCES chat_runtime_tasks(environment, task_id),
    CHECK (state IN ('RESERVED', 'CONNECTED', 'GRACE', 'OFFLINE', 'LEFT')),
    CHECK (owner_generation >= 0),
    CHECK ((owner_task_id IS NULL AND socket_token IS NULL) OR
           (owner_task_id IS NOT NULL AND socket_token IS NOT NULL)),
    INDEX idx_chat_runtime_seats (environment, epoch, room_id, state, deadline, user_id),
    INDEX idx_chat_runtime_member (membership_id, state),
    INDEX idx_chat_runtime_owner (environment, owner_task_id, state)
);
