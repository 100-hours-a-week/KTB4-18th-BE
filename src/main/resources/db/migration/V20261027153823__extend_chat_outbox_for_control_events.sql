ALTER TABLE chat_view_runtime ADD COLUMN end_reason VARCHAR(32) NULL;
ALTER TABLE chat_room_members ADD COLUMN end_reason VARCHAR(32) NULL;
-- Multiple terminal references and a presence hint can share one committed room transition.
ALTER TABLE chat_message_outbox
    MODIFY COLUMN message_id BIGINT NULL,
    ADD COLUMN event_type VARCHAR(32) NOT NULL DEFAULT 'CHAT_MESSAGE_CREATED',
    ADD COLUMN event_body TEXT NULL,
    DROP INDEX uk_chat_outbox_room_seq,
    ADD INDEX idx_chat_outbox_room_order (environment,epoch,room_id,room_seq,event_id),
    ADD CONSTRAINT chk_chat_outbox_kind CHECK (event_type IN
        ('CHAT_MESSAGE_CREATED','CHAT_VIEW_ENDED','CHAT_MEMBERSHIP_ENDED','CHAT_PRESENCE_CHANGED')),
    ADD CONSTRAINT chk_chat_outbox_reference CHECK
        ((event_type='CHAT_MESSAGE_CREATED' AND message_id IS NOT NULL AND event_body IS NULL) OR
         (event_type<>'CHAT_MESSAGE_CREATED' AND message_id IS NULL AND event_body IS NOT NULL
          AND OCTET_LENGTH(event_body)<=16384 AND JSON_VALID(event_body)));
