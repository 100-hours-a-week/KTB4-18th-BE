ALTER TABLE music
    ADD COLUMN release_epoch_second BIGINT NULL,
    ALGORITHM=INSTANT;
