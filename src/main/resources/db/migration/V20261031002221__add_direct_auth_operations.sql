ALTER TABLE `auth_browser_flows`
    ADD COLUMN `flow_key_hash` CHAR(43) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    ADD COLUMN `flow_cookie_generation` BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN `binding_cookie_generation` BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN `binding_proof_hmac` BINARY(32) NULL DEFAULT NULL,
    ADD COLUMN `binding_cookie_expires_at` DATETIME(6) NULL DEFAULT NULL,
    ADD COLUMN `active_login_operation_id` CHAR(36) NULL DEFAULT NULL,
    ADD COLUMN `current_direct_operation_kind` VARCHAR(16) NULL DEFAULT NULL,
    ADD COLUMN `current_direct_operation_id` CHAR(36) NULL DEFAULT NULL,
    ADD COLUMN `current_refresh_recovery_operation_id` CHAR(36) NULL DEFAULT NULL,
    ADD COLUMN `current_refresh_recovery_source_operation_id` CHAR(36) NULL DEFAULT NULL,
    ADD COLUMN `current_refresh_recovery_status` VARCHAR(24) NULL DEFAULT NULL,
    ADD COLUMN `current_refresh_recovery_lease_expires_at` DATETIME(6) NULL DEFAULT NULL,
    ADD COLUMN `current_refresh_recovery_claim_flow_cookie_generation` BIGINT NULL DEFAULT NULL,
    ADD COLUMN `current_refresh_recovery_claim_refresh_generation` BIGINT NULL DEFAULT NULL,
    ADD COLUMN `current_refresh_recovery_result_flow_cookie_generation` BIGINT NULL DEFAULT NULL,
    ADD COLUMN `current_refresh_recovery_result_refresh_generation` BIGINT NULL DEFAULT NULL,
    ADD UNIQUE INDEX `UK_AUTH_BROWSER_FLOWS_FLOW_KEY_HASH` (`flow_key_hash`),
    ALGORITHM=INPLACE,
    LOCK=NONE;

ALTER TABLE `auth_browser_sessions`
    ADD COLUMN `refresh_cookie_mode` VARCHAR(24) NOT NULL DEFAULT 'LEGACY';

CREATE TABLE `auth_login_operations` (
    `flow_id` CHAR(36) NOT NULL,
    `operation_id` CHAR(36) NOT NULL,
    `starting_epoch` BIGINT NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `proof_generation` BIGINT NULL,
    `lease_expires_at` DATETIME(6) NULL,
    `receipt_expires_at` DATETIME(6) NULL,
    `tombstone_purge_at` DATETIME(6) NULL,
    `result_epoch` BIGINT NULL,
    `result_revision` BIGINT NULL,
    `result_sid` VARCHAR(64) NULL,
    `reason_code` VARCHAR(40) NULL,
    `result_flow_cookie_generation` BIGINT NULL,
    `result_refresh_generation` BIGINT NULL,
    `recovery_result_epoch` BIGINT NULL,
    `recovery_result_revision` BIGINT NULL,
    `recovery_flow_cookie_generation` BIGINT NULL,
    `recovery_refresh_generation` BIGINT NULL,
    `recovery_completed_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    `completed_at` DATETIME(6) NULL,
    CONSTRAINT `PK_AUTH_LOGIN_OPERATIONS` PRIMARY KEY (`flow_id`, `operation_id`),
    INDEX `IDX_AUTH_LOGIN_OPERATIONS_FLOW_STATUS_EXPIRY` (`flow_id`, `status`, `receipt_expires_at`),
    INDEX `IDX_AUTH_LOGIN_OPERATIONS_RECEIPT_EXPIRY` (`receipt_expires_at`, `flow_id`, `operation_id`),
    INDEX `IDX_AUTH_LOGIN_OPERATIONS_TOMBSTONE_PURGE` (`tombstone_purge_at`, `flow_id`, `operation_id`),
    INDEX `IDX_AUTH_LOGIN_OPERATIONS_LEASE_EXPIRY` (`status`, `lease_expires_at`, `flow_id`, `operation_id`),
    CONSTRAINT `FK_AUTH_LOGIN_OPERATIONS_FLOW` FOREIGN KEY (`flow_id`) REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE `auth_direct_operation_receipts` (
    `flow_id` CHAR(36) NOT NULL,
    `operation_kind` VARCHAR(16) NOT NULL,
    `operation_id` CHAR(36) NOT NULL,
    `starting_epoch` BIGINT NOT NULL,
    `original_flow_cookie_generation` BIGINT NULL,
    `original_refresh_token_hmac` BINARY(32) NULL,
    `result_epoch` BIGINT NULL,
    `result_revision` BIGINT NULL,
    `result_flow_cookie_generation` BIGINT NULL,
    `result_refresh_generation` BIGINT NULL,
    `status` VARCHAR(16) NOT NULL,
    `receipt_expires_at` DATETIME(6) NOT NULL,
    `tombstone_purge_at` DATETIME(6) NOT NULL,
    `encrypted_result_response_body` TEXT NULL,
    `encrypted_result_refresh_token` TEXT NULL,
    `expired_cookie_names` TEXT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `completed_at` DATETIME(6) NULL,
    CONSTRAINT `PK_AUTH_DIRECT_OPERATION_RECEIPTS` PRIMARY KEY (`flow_id`, `operation_kind`, `operation_id`),
    INDEX `IDX_AUTH_DIRECT_OPERATION_RECEIPTS_FLOW_EXPIRY` (`flow_id`, `receipt_expires_at`),
    INDEX `IDX_AUTH_DIRECT_OPERATION_RECEIPTS_RECEIPT_EXPIRY` (`receipt_expires_at`, `flow_id`, `operation_kind`, `operation_id`),
    INDEX `IDX_AUTH_DIRECT_OPERATION_RECEIPTS_TOMBSTONE_PURGE` (`tombstone_purge_at`, `flow_id`, `operation_kind`, `operation_id`),
    CONSTRAINT `FK_AUTH_DIRECT_OPERATION_RECEIPTS_FLOW` FOREIGN KEY (`flow_id`) REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE `auth_flow_rebootstrap_operations` (
    `flow_id` CHAR(36) NOT NULL,
    `operation_id` CHAR(36) NOT NULL,
    `starting_epoch` BIGINT NOT NULL,
    `binding_generation` BIGINT NULL,
    `status` VARCHAR(16) NOT NULL,
    `result_epoch` BIGINT NULL,
    `result_revision` BIGINT NULL,
    `result_flow_cookie_generation` BIGINT NULL,
    `receipt_expires_at` DATETIME(6) NOT NULL,
    `tombstone_purge_at` DATETIME(6) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `completed_at` DATETIME(6) NULL,
    `result_flow_cookie_ciphertext` TEXT NULL DEFAULT NULL,
    CONSTRAINT `PK_AUTH_FLOW_REBOOTSTRAP_OPERATIONS` PRIMARY KEY (`flow_id`, `operation_id`),
    INDEX `IDX_AUTH_FLOW_REBOOTSTRAP_OPS_FLOW_EXPIRY` (`flow_id`, `receipt_expires_at`),
    INDEX `IDX_AUTH_FLOW_REBOOTSTRAP_OPS_RECEIPT_EXPIRY` (`receipt_expires_at`, `flow_id`, `operation_id`),
    INDEX `IDX_AUTH_FLOW_REBOOTSTRAP_OPS_TOMBSTONE_PURGE` (`tombstone_purge_at`, `flow_id`, `operation_id`),
    CONSTRAINT `FK_AUTH_FLOW_REBOOTSTRAP_OPERATIONS_FLOW` FOREIGN KEY (`flow_id`) REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE `auth_refresh_recovery_operations` (
    `flow_id` CHAR(36) NOT NULL,
    `recovery_operation_id` CHAR(36) NOT NULL,
    `source_refresh_operation_id` CHAR(36) NOT NULL,
    `starting_epoch` BIGINT NOT NULL,
    `binding_generation` BIGINT NOT NULL,
    `claim_flow_cookie_generation` BIGINT NOT NULL,
    `claim_refresh_generation` BIGINT NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `owner_token` BINARY(16) NULL DEFAULT NULL,
    `lease_expires_at` DATETIME(6) NULL DEFAULT NULL,
    `result_session_sid` VARCHAR(64) NULL DEFAULT NULL,
    `result_epoch` BIGINT NULL DEFAULT NULL,
    `result_revision` BIGINT NULL DEFAULT NULL,
    `result_flow_cookie_generation` BIGINT NULL DEFAULT NULL,
    `result_refresh_generation` BIGINT NULL DEFAULT NULL,
    `encrypted_result_response_body` BLOB NULL DEFAULT NULL,
    `encrypted_result_refresh_cookie` BLOB NULL DEFAULT NULL,
    `encrypted_result_flow_cookie` BLOB NULL DEFAULT NULL,
    `receipt_expires_at` DATETIME(6) NOT NULL,
    `tombstone_purge_at` DATETIME(6) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    `completed_at` DATETIME(6) NULL DEFAULT NULL,
    CONSTRAINT `PK_AUTH_REFRESH_RECOVERY_OPERATIONS` PRIMARY KEY (`flow_id`, `recovery_operation_id`),
    INDEX `IDX_AUTH_REFRESH_RECOVERY_SOURCE` (`flow_id`, `source_refresh_operation_id`, `created_at`, `recovery_operation_id`),
    INDEX `IDX_AUTH_REFRESH_RECOVERY_LEASE` (`status`, `lease_expires_at`, `flow_id`, `recovery_operation_id`),
    INDEX `IDX_AUTH_REFRESH_RECOVERY_RECEIPT_EXPIRY` (`receipt_expires_at`, `flow_id`, `recovery_operation_id`),
    INDEX `IDX_AUTH_REFRESH_RECOVERY_TOMBSTONE_PURGE` (`tombstone_purge_at`, `flow_id`, `recovery_operation_id`),
    CONSTRAINT `FK_AUTH_REFRESH_RECOVERY_OPERATIONS_FLOW` FOREIGN KEY (`flow_id`) REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE `auth_flow_bootstrap_operations` (
    `operation_id` CHAR(36) NOT NULL,
    `request_fingerprint` BINARY(32) NOT NULL,
    `initial_proof_fingerprint` BINARY(32) NULL DEFAULT NULL,
    `flow_id` CHAR(36) NULL DEFAULT NULL,
    `status` VARCHAR(16) NOT NULL,
    `result_flow_status` VARCHAR(24) NULL DEFAULT NULL,
    `result_epoch` BIGINT NULL DEFAULT NULL,
    `result_revision` BIGINT NULL DEFAULT NULL,
    `result_flow_cookie_generation` BIGINT NULL DEFAULT NULL,
    `result_binding_cookie_generation` BIGINT NULL DEFAULT NULL,
    `encrypted_exact_response` BLOB NULL DEFAULT NULL,
    `receipt_expires_at` DATETIME(6) NOT NULL,
    `tombstone_purge_at` DATETIME(6) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    `completed_at` DATETIME(6) NULL DEFAULT NULL,
    CONSTRAINT `PK_AUTH_FLOW_BOOTSTRAP_OPERATIONS` PRIMARY KEY (`operation_id`),
    INDEX `IDX_AUTH_FLOW_BOOTSTRAP_FLOW_STATUS_EXPIRY` (`flow_id`, `status`, `receipt_expires_at`),
    INDEX `IDX_AUTH_FLOW_BOOTSTRAP_RECEIPT_EXPIRY` (`receipt_expires_at`, `operation_id`),
    INDEX `IDX_AUTH_FLOW_BOOTSTRAP_TOMBSTONE_PURGE` (`tombstone_purge_at`, `operation_id`)
) ENGINE=InnoDB;

CREATE TABLE `auth_replay_iv_allocators` (
    `key_id` VARCHAR(128) NOT NULL,
    `next_iv` BINARY(12) NOT NULL,
    `exhausted` BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT `PK_AUTH_REPLAY_IV_ALLOCATORS` PRIMARY KEY (`key_id`)
) ENGINE=InnoDB;

CREATE TABLE `auth_replay_key_pin` (
    `singleton_id` TINYINT NOT NULL,
    `key_id` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT `PK_AUTH_REPLAY_KEY_PIN` PRIMARY KEY (`singleton_id`)
) ENGINE=InnoDB;
