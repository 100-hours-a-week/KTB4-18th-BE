CREATE TEMPORARY TABLE `auth_recovery_migration_preflight` (
    `valid_references` TINYINT NOT NULL,
    CONSTRAINT `CHK_AUTH_RECOVERY_MIGRATION_PREFLIGHT` CHECK (`valid_references` = 1)
);

INSERT INTO `auth_recovery_migration_preflight` (`valid_references`)
SELECT CASE WHEN EXISTS (
    SELECT 1 FROM `auth_refresh_recovery_operations` r
    LEFT JOIN `auth_direct_operation_receipts` s
        ON s.`flow_id` = r.`flow_id`
        AND BINARY s.`operation_kind` = _binary 'REFRESH'
        AND s.`operation_id` = r.`source_refresh_operation_id`
    WHERE s.`flow_id` IS NULL
) THEN 0 ELSE 1 END;

DROP TEMPORARY TABLE `auth_recovery_migration_preflight`;

ALTER TABLE `auth_login_operations`
    ADD COLUMN `owner_token` BINARY(16) NULL DEFAULT NULL;

ALTER TABLE `auth_direct_operation_receipts`
    MODIFY COLUMN `operation_kind` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ADD COLUMN `result_session_sid` VARCHAR(64) NULL DEFAULT NULL,
    ADD COLUMN `original_flow_cookie_hmac` BINARY(32) NULL DEFAULT NULL,
    ADD COLUMN `encrypted_result_flow_cookie` TEXT NULL DEFAULT NULL,
    ADD COLUMN `client_expires_at_ms` BIGINT NULL DEFAULT NULL,
    ADD COLUMN `effective_expires_at_ms` BIGINT NULL DEFAULT NULL;

ALTER TABLE `auth_refresh_recovery_operations`
    MODIFY COLUMN `binding_generation` BIGINT NULL DEFAULT NULL,
    MODIFY COLUMN `claim_flow_cookie_generation` BIGINT NULL DEFAULT NULL,
    MODIFY COLUMN `claim_refresh_generation` BIGINT NULL DEFAULT NULL,
    ADD COLUMN `client_expires_at_ms` BIGINT NULL DEFAULT NULL,
    ADD COLUMN `effective_expires_at_ms` BIGINT NULL DEFAULT NULL,
    ADD COLUMN `source_operation_kind` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'REFRESH',
    ADD CONSTRAINT `CHK_AUTH_REFRESH_RECOVERY_SOURCE_KIND` CHECK (BINARY `source_operation_kind` = _binary 'REFRESH'),
    ADD INDEX `IDX_AUTH_REFRESH_RECOVERY_SOURCE_FK` (`flow_id`, `source_operation_kind`, `source_refresh_operation_id`),
    ADD CONSTRAINT `FK_AUTH_REFRESH_RECOVERY_SOURCE` FOREIGN KEY (`flow_id`, `source_operation_kind`, `source_refresh_operation_id`)
        REFERENCES `auth_direct_operation_receipts` (`flow_id`, `operation_kind`, `operation_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT;
