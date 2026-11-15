ALTER TABLE `auth_browser_sessions`
    ADD COLUMN `direct_projection_access_jti` CHAR(36) NULL DEFAULT NULL,
    ADD COLUMN `direct_projection_access_expires_at` DATETIME(6) NULL DEFAULT NULL;

ALTER TABLE `auth_direct_projection_outbox`
    ADD INDEX `IDX_AUTH_DIRECT_PROJECTION_RETAINED` (`processed_at`, `created_at`, `event_id`);

ALTER TABLE `auth_direct_session_fences`
    ADD COLUMN `retained_until` DATETIME(6) NULL DEFAULT NULL,
    ADD INDEX `IDX_AUTH_DIRECT_FENCE_RETAINED` (`retained_until`, `sid`);
