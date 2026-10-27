CREATE TABLE chat_send_policy (
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    last_accepted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (environment,user_id)
);
CREATE TABLE chat_send_repetitions (
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    membership_id BIGINT NOT NULL,
    key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    accepted_count INT NOT NULL,
    PRIMARY KEY (environment,membership_id,key_id,fingerprint),
    CHECK (accepted_count BETWEEN 1 AND 2)
);
CREATE TABLE chat_message_receipts (
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    client_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    membership_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (environment,user_id,client_message_id),
    INDEX idx_chat_receipt_expiry (expires_at),
    FOREIGN KEY (message_id) REFERENCES chat_room_messages(id) ON DELETE CASCADE
);
-- Reference notifications only: no message bodies, nicknames, tokens or raw content hashes.
CREATE TABLE chat_message_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    environment VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    epoch BIGINT NOT NULL,
    producer_id VARCHAR(41) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    room_id BIGINT NOT NULL,
    room_seq BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'PENDING',
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_chat_outbox_message (environment,message_id),
    UNIQUE KEY uk_chat_outbox_room_seq (environment,epoch,room_id,room_seq),
    INDEX idx_chat_outbox_dispatch (environment,status,next_attempt_at,room_id),
    INDEX idx_chat_outbox_expiry (expires_at),
    CHECK (status IN ('PENDING','SENT','EXPIRED'))
);
ALTER TABLE chat_room_runtime
    ADD COLUMN dispatch_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN dispatch_until DATETIME(6) NULL;
