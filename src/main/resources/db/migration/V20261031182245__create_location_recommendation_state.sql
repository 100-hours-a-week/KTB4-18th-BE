CREATE TABLE location_recommendation_states (
    user_id BIGINT NOT NULL,
    scope_generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cancel_epoch BIGINT NOT NULL DEFAULT 0,
    lease_request_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_region_id BIGINT NULL,
    lease_expires_at DATETIME(6) NULL,
    active_request_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_location_recommendation_states PRIMARY KEY (user_id),
    CONSTRAINT fk_location_recommendation_states_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_location_recommendation_states_epoch CHECK (cancel_epoch >= 0),
    CONSTRAINT ck_location_recommendation_states_lease CHECK (
        (lease_request_uuid IS NULL AND lease_region_id IS NULL AND lease_expires_at IS NULL)
        OR (lease_request_uuid IS NOT NULL AND lease_region_id IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    CONSTRAINT fk_location_recommendation_states_region FOREIGN KEY (lease_region_id) REFERENCES regions (id)
);

CREATE TABLE location_recommendation_requests (
    user_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    canonical_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    scope_generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region_id BIGINT NOT NULL,
    scope_date DATE NOT NULL,
    issued_date DATE NULL,
    issued_at DATETIME(6) NULL,
    status VARCHAR(20) NOT NULL,
    admitted_cancel_epoch BIGINT NOT NULL,
    expires_at DATETIME(6) NULL,
    display_claim_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    display_claimed_at DATETIME(6) NULL,
    tracks JSON NOT NULL,
    error_reason VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_location_recommendation_requests PRIMARY KEY (user_id, request_id),
    CONSTRAINT uk_location_recommendation_requests_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_location_recommendation_requests_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_location_recommendation_requests_region FOREIGN KEY (region_id) REFERENCES regions (id),
    CONSTRAINT fk_location_recommendation_requests_canonical FOREIGN KEY (user_id, canonical_request_id)
        REFERENCES location_recommendation_requests (user_id, request_id) ON DELETE CASCADE,
    CONSTRAINT ck_location_recommendation_requests_status CHECK (
        status IN ('PROCESSING', 'READY', 'EMPTY', 'FAILED', 'CANCELLED', 'SUPERSEDED', 'JOINED')
    ),
    CONSTRAINT ck_location_recommendation_requests_alias CHECK (
        (canonical_request_id IS NULL AND status <> 'JOINED')
        OR (canonical_request_id IS NOT NULL AND status = 'JOINED')
    ),
    CONSTRAINT ck_location_recommendation_requests_epoch CHECK (admitted_cancel_epoch >= 0),
    INDEX idx_location_recommendation_requests_scope (user_id, region_id, scope_date, status),
    INDEX idx_location_recommendation_requests_retention (created_at, canonical_request_id),
    INDEX idx_location_recommendation_requests_issue (user_id, region_id, issued_date)
);

CREATE TABLE location_recommendation_daily_issues (
    user_id BIGINT NOT NULL,
    region_id BIGINT NOT NULL,
    issued_date DATE NOT NULL,
    canonical_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_location_recommendation_daily_issues PRIMARY KEY (user_id, region_id, issued_date),
    CONSTRAINT uk_location_recommendation_daily_issues_canonical UNIQUE (user_id, canonical_request_id),
    CONSTRAINT fk_location_recommendation_daily_issues_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_location_recommendation_daily_issues_region FOREIGN KEY (region_id) REFERENCES regions (id),
    CONSTRAINT fk_location_recommendation_daily_issues_request FOREIGN KEY (user_id, canonical_request_id)
        REFERENCES location_recommendation_requests (user_id, request_id) ON DELETE CASCADE,
    INDEX idx_location_recommendation_daily_issues_retention (issued_date)
);

CREATE TABLE location_recommendation_cancellations (
    user_id BIGINT NOT NULL,
    cancellation_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_request_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_cancel_epoch BIGINT NOT NULL,
    result_cancel_epoch BIGINT NOT NULL,
    result_code VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_location_recommendation_cancellations PRIMARY KEY (user_id, cancellation_uuid),
    CONSTRAINT fk_location_recommendation_cancellations_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_location_recommendation_cancellations_expected_epoch CHECK (expected_cancel_epoch >= 0),
    CONSTRAINT ck_location_recommendation_cancellations_result_epoch CHECK (
        result_cancel_epoch = expected_cancel_epoch + 1
    ),
    CONSTRAINT ck_location_recommendation_cancellations_result CHECK (result_code = 'CANCELLED'),
    INDEX idx_location_recommendation_cancellations_retention (expires_at)
);

CREATE INDEX idx_music_records_user_region_active
    ON music_records (user_id, region_id, deleted_at);
