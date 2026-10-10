CREATE TABLE `chat_bans` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `banned_by_user_id` BIGINT NULL,
    `reason` VARCHAR(64) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    `deleted_at` DATETIME(6) NULL,
    CONSTRAINT `PK_CHAT_BANS` PRIMARY KEY (`id`),
    CONSTRAINT `FK_CHAT_BANS_USER` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `FK_CHAT_BANS_ACTOR` FOREIGN KEY (`banned_by_user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `CK_CHAT_BANS_PERIOD` CHECK (`expires_at` > `created_at`),
    CONSTRAINT `CK_CHAT_BANS_REASON` CHECK (`reason` IN ('PROFANITY', 'OBSCENITY')),
    INDEX `IDX_CHAT_BANS_USER_EXPIRY` (`user_id`, `deleted_at`, `expires_at`)
) DEFAULT CHARACTER SET utf8mb4;
