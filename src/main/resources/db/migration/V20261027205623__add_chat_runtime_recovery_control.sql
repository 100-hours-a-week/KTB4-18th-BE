ALTER TABLE chat_runtime_control
    ADD COLUMN recovery_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recovery_lease_until DATETIME(6) NULL,
    ADD COLUMN redis_generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN redis_server_id CHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT chk_chat_recovery_lease CHECK
        ((recovery_token IS NULL AND recovery_lease_until IS NULL) OR
         (recovery_token IS NOT NULL AND recovery_lease_until IS NOT NULL));
