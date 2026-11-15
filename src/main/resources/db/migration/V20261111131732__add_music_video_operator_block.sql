-- Automatic metadata refresh must not undo an explicit operator disable decision.
ALTER TABLE music_video_sources ADD COLUMN operator_blocked BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE music_video_sources s JOIN music_playback_sources p
    ON p.music_id=s.music_id AND p.youtube_video_id=s.youtube_video_id
SET s.operator_blocked=TRUE WHERE p.status='UNAVAILABLE';
