-- Keep immutable candidate rows and duration validation; expand only the allowed rank range.
ALTER TABLE music_video_candidates
    DROP CHECK ck_music_video_candidate_numbers,
    ADD CONSTRAINT ck_music_video_candidate_numbers CHECK (rank_no BETWEEN 1 AND 50
        AND duration_millis BETWEEN 1 AND 9999999999);
