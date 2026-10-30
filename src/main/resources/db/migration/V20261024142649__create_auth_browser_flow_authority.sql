CREATE TABLE `auth_browser_flows` (
    `flow_id` CHAR(36) NOT NULL,
    `flow_cookie_hash` CHAR(64) NOT NULL,
    `flow_epoch` BIGINT NOT NULL DEFAULT 1,
    `revision` BIGINT NOT NULL DEFAULT 1,
    `lifecycle` VARCHAR(16) NOT NULL DEFAULT 'ANONYMOUS',
    `active_session_sid` VARCHAR(64) NULL,
    `current_ticket_id` VARCHAR(64) NULL,
    `cookie_expires_at` DATETIME(6) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    CONSTRAINT `PK_AUTH_BROWSER_FLOWS` PRIMARY KEY (`flow_id`),
    CONSTRAINT `UK_AUTH_BROWSER_FLOWS_COOKIE_HASH` UNIQUE (`flow_cookie_hash`),
    CONSTRAINT `UK_AUTH_BROWSER_FLOWS_CURRENT_TICKET` UNIQUE (`current_ticket_id`),
    INDEX `IDX_AUTH_BROWSER_FLOWS_SESSION` (`active_session_sid`),
    INDEX `IDX_AUTH_BROWSER_FLOWS_EXPIRY` (`cookie_expires_at`)
) ENGINE=InnoDB;

CREATE TABLE `auth_browser_sessions` (
    `sid` VARCHAR(64) NOT NULL,
    `flow_id` CHAR(36) NOT NULL,
    `flow_epoch` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `status` VARCHAR(16) NOT NULL,
    `idle_expires_at` DATETIME(6) NOT NULL,
    `absolute_expires_at` DATETIME(6) NOT NULL,
    `current_refresh_token_hash` CHAR(64) NULL,
    `encrypted_current_refresh_token` TEXT NULL,
    `refresh_generation` BIGINT NOT NULL DEFAULT 1,
    `revoked_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    CONSTRAINT `PK_AUTH_BROWSER_SESSIONS` PRIMARY KEY (`sid`),
    INDEX `IDX_AUTH_BROWSER_SESSIONS_FLOW` (`flow_id`, `status`, `sid`),
    INDEX `IDX_AUTH_BROWSER_SESSIONS_USER` (`user_id`, `status`, `sid`),
    CONSTRAINT `FK_AUTH_BROWSER_SESSIONS_FLOW`
        FOREIGN KEY (`flow_id`) REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `auth_flow_operations` (
    `flow_id` CHAR(36) NOT NULL,
    `operation_kind` VARCHAR(32) NOT NULL,
    `operation_id` CHAR(36) NOT NULL,
    `request_hash` CHAR(64) NOT NULL,
    `started_epoch` BIGINT NOT NULL,
    `result_epoch` BIGINT NOT NULL,
    `result_revision` BIGINT NOT NULL,
    `result_generation` BIGINT NULL,
    `encrypted_result_refresh_token` TEXT NULL,
    `committed_at` DATETIME(6) NOT NULL,
    CONSTRAINT `PK_AUTH_FLOW_OPERATIONS` PRIMARY KEY (`flow_id`, `operation_kind`, `operation_id`),
    CONSTRAINT `FK_AUTH_FLOW_OPERATIONS_FLOW`
        FOREIGN KEY (`flow_id`) REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `auth_queue_epoch_slots` (
    `flow_id` CHAR(36) NOT NULL,
    `flow_epoch` BIGINT NOT NULL,
    `active_intent_id` CHAR(36) NULL,
    `reserved_revision` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    CONSTRAINT `PK_AUTH_QUEUE_EPOCH_SLOTS` PRIMARY KEY (`flow_id`, `flow_epoch`),
    CONSTRAINT `UK_AUTH_QUEUE_EPOCH_SLOTS_INTENT` UNIQUE (`active_intent_id`),
    CONSTRAINT `FK_AUTH_QUEUE_EPOCH_SLOTS_FLOW`
        FOREIGN KEY (`flow_id`) REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `auth_queue_create_intents` (
    `intent_id` CHAR(36) NOT NULL,
    `flow_id` CHAR(36) NOT NULL,
    `flow_epoch` BIGINT NOT NULL,
    `request_hash` CHAR(64) NOT NULL,
    `state` VARCHAR(16) NOT NULL,
    `ticket_id` VARCHAR(64) NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    CONSTRAINT `PK_AUTH_QUEUE_CREATE_INTENTS` PRIMARY KEY (`intent_id`),
    INDEX `IDX_AUTH_QUEUE_INTENTS_FLOW_EPOCH` (`flow_id`, `flow_epoch`, `state`),
    CONSTRAINT `FK_AUTH_QUEUE_CREATE_INTENTS_FLOW`
        FOREIGN KEY (`flow_id`) REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `auth_queue_create_operations` (
    `flow_id` CHAR(36) NOT NULL,
    `operation_id` CHAR(36) NOT NULL,
    `request_hash` CHAR(64) NOT NULL,
    `intent_id` CHAR(36) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    CONSTRAINT `PK_AUTH_QUEUE_CREATE_OPERATIONS` PRIMARY KEY (`flow_id`, `operation_id`),
    INDEX `IDX_AUTH_QUEUE_CREATE_OPERATIONS_INTENT` (`intent_id`),
    CONSTRAINT `FK_AUTH_QUEUE_CREATE_OPERATIONS_FLOW`
        FOREIGN KEY (`flow_id`) REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE CASCADE,
    CONSTRAINT `FK_AUTH_QUEUE_CREATE_OPERATIONS_INTENT`
        FOREIGN KEY (`intent_id`) REFERENCES `auth_queue_create_intents` (`intent_id`) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `auth_flow_outbox` (
    `event_id` BIGINT NOT NULL AUTO_INCREMENT,
    `flow_id` CHAR(36) NOT NULL,
    `flow_epoch` BIGINT NOT NULL,
    `revision` BIGINT NOT NULL,
    `event_kind` VARCHAR(32) NOT NULL,
    `target_id` VARCHAR(64) NULL,
    `created_at` DATETIME(6) NOT NULL,
    `processed_at` DATETIME(6) NULL,
    `attempts` INT NOT NULL DEFAULT 0,
    `claim_id` CHAR(36) NULL,
    `claimed_until` DATETIME(6) NULL,
    CONSTRAINT `PK_AUTH_FLOW_OUTBOX` PRIMARY KEY (`event_id`),
    INDEX `IDX_AUTH_FLOW_OUTBOX_PENDING` (`processed_at`, `claimed_until`, `event_id`),
    INDEX `IDX_AUTH_FLOW_OUTBOX_FLOW_VERSION` (`flow_id`, `flow_epoch`, `revision`),
    CONSTRAINT `FK_AUTH_FLOW_OUTBOX_FLOW`
        FOREIGN KEY (`flow_id`) REFERENCES `auth_browser_flows` (`flow_id`) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `auth_session_refresh_token_history` (
    `token_hash` CHAR(64) NOT NULL,
    `sid` VARCHAR(64) NOT NULL,
    `consumed_at` DATETIME(6) NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    CONSTRAINT `PK_AUTH_SESSION_REFRESH_TOKEN_HISTORY` PRIMARY KEY (`token_hash`),
    INDEX `IDX_AUTH_SESSION_REFRESH_TOKEN_HISTORY_SID` (`sid`, `expires_at`),
    CONSTRAINT `FK_AUTH_SESSION_REFRESH_TOKEN_HISTORY_SESSION`
        FOREIGN KEY (`sid`) REFERENCES `auth_browser_sessions` (`sid`) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `auth_flow_bootstrap_rate_limits` (
    `address_hash` CHAR(64) NOT NULL,
    `window_started_at` DATETIME(6) NOT NULL,
    `request_count` INT NOT NULL DEFAULT 1,
    `updated_at` DATETIME(6) NOT NULL,
    CONSTRAINT `PK_AUTH_FLOW_BOOTSTRAP_RATE_LIMITS` PRIMARY KEY (`address_hash`, `window_started_at`),
    INDEX `IDX_AUTH_FLOW_BOOTSTRAP_RATE_LIMITS_WINDOW` (`window_started_at`)
) ENGINE=InnoDB;
