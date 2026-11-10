-- Read-only operational evidence. No credentials, user ids, messages or source URLs are selected.
-- Run only against the intended environment; these aggregates do not replace requestable-song API smoke.
SELECT version, description, success
FROM flyway_schema_history
WHERE version IN ('20261108192420','20261109103259','20261109132852')
ORDER BY installed_rank;

SELECT status, playback_country, COUNT(*) AS sources,
       SUM(status='VERIFIED' AND playback_country='KR' AND valid_until>UTC_TIMESTAMP(6)
           AND duration_millis>0) AS currently_valid
FROM music_playback_sources GROUP BY status, playback_country;

SELECT state, COUNT(*) AS rooms,
       SUM(state<>'IDLE' AND (source_video_id IS NULL OR duration_millis IS NULL
           OR (state='PLAYING' AND due_at IS NULL))) AS invalid_active_pins
FROM playback_states GROUP BY state;

SELECT status, COUNT(*) AS queue_items FROM music_queue_items GROUP BY status;

SELECT environment, epoch, mode, recovery_lease_until
FROM chat_runtime_control;

SELECT environment, epoch, state, COUNT(*) AS tasks,
       SUM(lease_until<=UTC_TIMESTAMP(6)) AS expired_leases
FROM chat_runtime_tasks GROUP BY environment, epoch, state;

SELECT environment, epoch, status, COUNT(*) AS events,
       MAX(TIMESTAMPDIFF(SECOND,occurred_at,UTC_TIMESTAMP(6))) AS oldest_age_seconds
FROM chat_music_outbox GROUP BY environment, epoch, status;

-- Nonzero rows require all referenced key IDs to remain available; never print key values.
SELECT environment, key_id, COUNT(*) AS retained_receipts,
       SUM(expires_at<=UTC_TIMESTAMP(6)) AS expired_tombstones
FROM chat_music_command_receipts GROUP BY environment, key_id;
