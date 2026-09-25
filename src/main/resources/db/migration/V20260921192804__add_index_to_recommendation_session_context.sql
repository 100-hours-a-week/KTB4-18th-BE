CREATE INDEX idx_recommendation_sessions_user_context
    ON recommendation_sessions (user_id, conversation_key, status, id);

CREATE INDEX idx_recommendation_sessions_guest_context
    ON recommendation_sessions (guest_session_id, conversation_key, status, id);
