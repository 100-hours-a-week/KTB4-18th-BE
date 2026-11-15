-- Room music data is canonical per database/schema; Redis environment is not storage isolation.
CREATE TABLE music_queue_items (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    music_id BIGINT NOT NULL,
    added_by_user_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WAITING',
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    playing_room_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='PLAYING' THEN room_id ELSE NULL END) STORED,
    CONSTRAINT fk_music_queue_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    CONSTRAINT fk_music_queue_music FOREIGN KEY (music_id) REFERENCES music(id),
    CONSTRAINT fk_music_queue_user FOREIGN KEY (added_by_user_id) REFERENCES users(id),
    CONSTRAINT uk_music_queue_sequence UNIQUE (room_id, sequence_no),
    CONSTRAINT uk_music_queue_room_item UNIQUE (room_id, id),
    CONSTRAINT uk_music_queue_playing UNIQUE (playing_room_id),
    CONSTRAINT ck_music_queue_sequence CHECK (sequence_no BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_music_queue_status CHECK (status IN ('WAITING','PLAYING','PLAYED','SKIPPED','CANCELED')),
    CONSTRAINT ck_music_queue_times CHECK (
        (status='WAITING' AND started_at IS NULL AND finished_at IS NULL) OR
        (status='PLAYING' AND started_at IS NOT NULL AND finished_at IS NULL) OR
        (status IN ('PLAYED','SKIPPED') AND started_at IS NOT NULL AND finished_at IS NOT NULL) OR
        (status='CANCELED' AND finished_at IS NOT NULL)),
    INDEX idx_music_queue_waiting (room_id, status, sequence_no),
    INDEX idx_music_queue_finished (finished_at, id)
);

CREATE TABLE playback_states (
    chat_room_id BIGINT NOT NULL PRIMARY KEY,
    current_queue_item_id BIGINT NULL,
    state VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'IDLE',
    position_seconds DECIMAL(10,3) NOT NULL DEFAULT 0,
    state_changed_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    version BIGINT NOT NULL DEFAULT 0,
    next_sequence_no BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    CONSTRAINT fk_playback_room FOREIGN KEY (chat_room_id) REFERENCES chat_rooms(id),
    CONSTRAINT fk_playback_same_room_item FOREIGN KEY (chat_room_id,current_queue_item_id)
        REFERENCES music_queue_items(room_id,id),
    CONSTRAINT ck_playback_state CHECK (state IN ('IDLE','PLAYING','PAUSED')),
    CONSTRAINT ck_playback_position CHECK (position_seconds >= 0),
    CONSTRAINT ck_playback_version CHECK (version BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_playback_sequence CHECK (next_sequence_no BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_playback_current CHECK (
        (state='IDLE' AND current_queue_item_id IS NULL AND position_seconds=0) OR
        (state IN ('PLAYING','PAUSED') AND current_queue_item_id IS NOT NULL))
);

-- Backfill only new state, without changing room IDs, membership or existing timestamps.
INSERT INTO playback_states (chat_room_id) SELECT id FROM chat_rooms;

-- Completed results only: no orphan PROCESSING receipts and no raw requests/tokens.
CREATE TABLE chat_music_command_receipts (
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    auth_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    membership_id BIGINT NOT NULL,
    view_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_version BIGINT NOT NULL,
    http_status SMALLINT NOT NULL,
    result_body TEXT NOT NULL,
    completed_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (environment,user_id,room_id,command_id),
    CONSTRAINT fk_music_receipt_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    CONSTRAINT fk_music_receipt_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_music_receipt_command CHECK (command_type IN ('ADD','CANCEL','PLAY','PAUSE','RESUME','SKIP')),
    CONSTRAINT ck_music_receipt_uuid CHECK (
        command_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' AND
        auth_session_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' AND
        view_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_music_receipt_identity CHECK (membership_id > 0 AND CHAR_LENGTH(environment)>0
        AND CHAR_LENGTH(key_id)>0 AND fingerprint REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_music_receipt_version CHECK (result_version BETWEEN 0 AND 9007199254740991),
    CONSTRAINT ck_music_receipt_response CHECK (http_status BETWEEN 200 AND 499
        AND OCTET_LENGTH(result_body)<=16384 AND JSON_VALID(result_body) AND JSON_TYPE(result_body)='OBJECT'),
    CONSTRAINT ck_music_receipt_expiry CHECK (expires_at > completed_at),
    INDEX idx_music_receipt_expiry (expires_at)
);

CREATE TABLE chat_music_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    epoch BIGINT NOT NULL,
    producer_id VARCHAR(41) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    room_id BIGINT NOT NULL,
    room_seq BIGINT NOT NULL,
    music_version BIGINT NOT NULL,
    event_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_body TEXT NOT NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    expires_at DATETIME(6) NOT NULL,
    status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    claim_until DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    sent_at DATETIME(6) NULL,
    CONSTRAINT fk_music_outbox_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    CONSTRAINT uk_music_outbox_transition UNIQUE (environment,epoch,room_id,music_version,event_type),
    CONSTRAINT ck_music_outbox_event CHECK (event_type IN ('QUEUE_UPDATED','PLAYBACK_UPDATED')),
    CONSTRAINT ck_music_outbox_uuid CHECK (
        event_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' AND
        (claim_token IS NULL OR claim_token REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')),
    CONSTRAINT ck_music_outbox_numbers CHECK (epoch>0 AND room_seq BETWEEN 1 AND 9007199254740991
        AND music_version BETWEEN 1 AND 9007199254740991 AND attempts>=0),
    CONSTRAINT ck_music_outbox_identity CHECK (CHAR_LENGTH(environment)>0 AND CHAR_LENGTH(producer_id)>0),
    CONSTRAINT ck_music_outbox_body CHECK (OCTET_LENGTH(event_body)<=16384
        AND JSON_VALID(event_body) AND JSON_TYPE(event_body)='OBJECT'),
    CONSTRAINT ck_music_outbox_status CHECK (status IN ('PENDING','SENT','EXPIRED')),
    CONSTRAINT ck_music_outbox_claim CHECK (
        (claim_token IS NULL AND claim_until IS NULL) OR
        (status='PENDING' AND claim_token IS NOT NULL AND claim_until IS NOT NULL)),
    CONSTRAINT ck_music_outbox_sent CHECK ((status='SENT' AND sent_at IS NOT NULL)
        OR (status<>'SENT' AND sent_at IS NULL)),
    CONSTRAINT ck_music_outbox_expiry CHECK (expires_at > occurred_at),
    INDEX idx_music_outbox_dispatch (environment,status,next_attempt_at,room_id,music_version),
    INDEX idx_music_outbox_room_order (environment,epoch,room_id,music_version,event_id),
    INDEX idx_music_outbox_expiry (expires_at,status)
);
