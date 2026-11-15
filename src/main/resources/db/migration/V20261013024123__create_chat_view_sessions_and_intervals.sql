CREATE TABLE `chat_view_sessions` (
    `id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `user_id` BIGINT NOT NULL,
    `room_id` BIGINT NOT NULL,
    `membership_id` BIGINT NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    `ended_at` DATETIME(6) NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `FK_CHAT_VIEW_USER` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `FK_CHAT_VIEW_ROOM` FOREIGN KEY (`room_id`) REFERENCES `chat_rooms` (`id`),
    CONSTRAINT `FK_CHAT_VIEW_MEMBER` FOREIGN KEY (`membership_id`) REFERENCES `chat_room_members` (`id`),
    INDEX `IDX_CHAT_VIEW_MEMBER` (`membership_id`),
    INDEX `IDX_CHAT_VIEW_UPDATED` (`updated_at`)
) DEFAULT CHARACTER SET utf8mb4;

CREATE TABLE `chat_view_intervals` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `view_session_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `process_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `started_at` DATETIME(6) NOT NULL,
    `start_after_message_id` BIGINT NOT NULL,
    `verified_at` DATETIME(6) NOT NULL,
    `verified_through_message_id` BIGINT NOT NULL,
    `ends_at` DATETIME(6) NULL,
    `end_message_id` BIGINT NULL,
    `end_reason` VARCHAR(20) NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `FK_CHAT_VIEW_INTERVAL_VIEW` FOREIGN KEY (`view_session_id`)
        REFERENCES `chat_view_sessions` (`id`) ON DELETE CASCADE,
    CONSTRAINT `CK_CHAT_VIEW_INTERVAL_START` CHECK (`start_after_message_id` >= 0),
    CONSTRAINT `CK_CHAT_VIEW_INTERVAL_VERIFIED` CHECK (
        `verified_at` >= `started_at` AND `verified_through_message_id` >= `start_after_message_id`),
    CONSTRAINT `CK_CHAT_VIEW_INTERVAL_END` CHECK (`ends_at` IS NULL OR `ends_at` >= `started_at`),
    INDEX `IDX_CHAT_VIEW_INTERVAL_VIEW` (`view_session_id`, `id`),
    INDEX `IDX_CHAT_VIEW_INTERVAL_END` (`ends_at`)
) DEFAULT CHARACTER SET utf8mb4;
