-- Policy transition: preserve history and release only active automatic profanity/obscenity bans.
UPDATE chat_bans
SET deleted_at = UTC_TIMESTAMP(6)
WHERE deleted_at IS NULL
  AND banned_by_user_id IS NULL
  AND reason IN ('PROFANITY', 'OBSCENITY')
  AND created_at <= UTC_TIMESTAMP(6)
  AND expires_at > UTC_TIMESTAMP(6);
