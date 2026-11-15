ALTER TABLE `auth_login_operations`
    ADD COLUMN `original_flow_cookie_hmac` BINARY(32) NULL DEFAULT NULL,
    ADD COLUMN `client_expires_at_ms` BIGINT NULL DEFAULT NULL,
    ADD COLUMN `effective_expires_at_ms` BIGINT NULL DEFAULT NULL;

CREATE TABLE `auth_direct_projection_control` (
    `singleton_id` TINYINT NOT NULL,
    `cookie_mode` VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
    `projection_generation` BIGINT NOT NULL DEFAULT 1,
    `readiness` VARCHAR(16) NOT NULL DEFAULT 'OFF',
    `replay_key_id` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
    `restore_verified` BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (`singleton_id`),
    CONSTRAINT `CHK_AUTH_DIRECT_CONTROL_SINGLETON` CHECK (`singleton_id` = 1),
    CONSTRAINT `CHK_AUTH_DIRECT_COOKIE_MODE` CHECK (`cookie_mode` IN ('LEGACY', 'GENERATION_SCOPED'))
) ENGINE=InnoDB;

INSERT INTO `auth_direct_projection_control` (`singleton_id`) VALUES (1);

CREATE TABLE `auth_direct_session_fences` (
    `sid` VARCHAR(64) NOT NULL,
    `flow_id` CHAR(36) NOT NULL,
    `flow_epoch` BIGINT NOT NULL,
    `revision` BIGINT NOT NULL,
    `projection_generation` BIGINT NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`sid`),
    CONSTRAINT `FK_AUTH_DIRECT_FENCE_FLOW` FOREIGN KEY (`flow_id`)
        REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE `auth_direct_projection_outbox` (
    `event_id` BIGINT NOT NULL AUTO_INCREMENT,
    `flow_id` CHAR(36) NOT NULL,
    `sid` VARCHAR(64) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `processed_at` DATETIME(6) NULL DEFAULT NULL,
    `claim_id` CHAR(36) NULL DEFAULT NULL,
    `lease_expires_at` DATETIME(6) NULL DEFAULT NULL,
    PRIMARY KEY (`event_id`),
    INDEX `IDX_AUTH_DIRECT_PROJECTION_PENDING` (`processed_at`, `lease_expires_at`, `event_id`),
    CONSTRAINT `FK_AUTH_DIRECT_PROJECTION_FLOW` FOREIGN KEY (`flow_id`)
        REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
