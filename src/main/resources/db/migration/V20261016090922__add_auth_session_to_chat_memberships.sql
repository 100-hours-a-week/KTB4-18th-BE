-- NULL identifies legacy memberships whose original authentication session is unknown.
-- Keep legacy writers protected until session-aware participation is deployed.
ALTER TABLE `chat_room_members`
    ADD COLUMN `auth_session_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY COLUMN `active_user_id` BIGINT GENERATED ALWAYS AS (
        CASE WHEN `deleted_at` IS NULL AND `auth_session_id` IS NULL THEN `user_id` ELSE NULL END
    ) STORED,
    ADD COLUMN `active_auth_session_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE WHEN `deleted_at` IS NULL THEN `auth_session_id` ELSE NULL END
        ) STORED,
    ADD CONSTRAINT `UK_CHAT_ROOM_MEMBERS_ACTIVE_AUTH_SESSION` UNIQUE (`active_auth_session_id`),
    ADD CONSTRAINT `CK_CHAT_ROOM_MEMBERS_AUTH_SESSION` CHECK (
        `auth_session_id` IS NULL OR REGEXP_LIKE(
            `auth_session_id`, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c')
    ),
    ADD INDEX `IDX_CHAT_ROOM_MEMBERS_AUTH_HISTORY` (`auth_session_id`, `deleted_at`);
