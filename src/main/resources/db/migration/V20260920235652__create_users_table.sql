CREATE TABLE `users` (
    `id` BIGINT NOT NULL,
    `email` VARCHAR(40) NOT NULL COMMENT 'UNIQUE KEY',
    `password_hash` VARCHAR(255) NOT NULL,
    `nickname` VARCHAR(12) NOT NULL,
    `birth_year` SMALLINT NULL,
    `gender` ENUM('MALE', 'FEMALE') NULL,
    `profile_image_url` VARCHAR(1000) NULL,
    `role` VARCHAR(20) NOT NULL DEFAULT 'USER',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NULL,
    `deleted_at` DATETIME NULL COMMENT 'soft delete',
    CONSTRAINT `PK_USERS` PRIMARY KEY (`id`),
    CONSTRAINT `UK_USERS_EMAIL` UNIQUE (`email`)
);
