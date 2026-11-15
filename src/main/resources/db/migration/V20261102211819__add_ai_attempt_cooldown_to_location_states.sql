ALTER TABLE location_recommendation_states
    ADD COLUMN last_ai_attempt_at DATETIME(6) NULL AFTER active_request_uuid;
