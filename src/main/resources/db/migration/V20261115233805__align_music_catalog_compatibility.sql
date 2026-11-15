SET @compat_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music'
    AND ((column_name='title_us' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>255))
        OR (column_name='artist_name_us' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>1000))
        OR (column_name='title_ko' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>255))
        OR (column_name='artist_name_ko' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>1000))
        OR (column_name='release_epoch_second' AND (data_type<>'bigint' OR is_nullable<>'YES'))))=0 AND NOT (EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='music_playback_sources')
    AND ((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music_playback_sources'
    AND ((column_name='music_id' AND data_type='bigint' AND is_nullable='NO')
        OR (column_name='youtube_video_id' AND data_type='varchar' AND is_nullable='NO' AND character_maximum_length=11 AND character_set_name='ascii' AND collation_name='ascii_bin')
        OR (column_name='channel_id' AND data_type='varchar' AND is_nullable='NO' AND character_maximum_length=64 AND character_set_name='ascii' AND collation_name='ascii_bin')
        OR (column_name='channel_kind' AND data_type='varchar' AND is_nullable='NO' AND character_maximum_length=20)
        OR (column_name='review_reference' AND data_type='varchar' AND is_nullable='NO' AND character_maximum_length=1000)
        OR (column_name='reviewed_by' AND data_type='varchar' AND is_nullable='NO' AND character_maximum_length=100)
        OR (column_name='status' AND data_type='varchar' AND is_nullable='NO' AND character_maximum_length=20)
        OR (column_name='duration_millis' AND data_type='bigint' AND is_nullable='NO')
        OR (column_name='playback_country' AND data_type='char' AND is_nullable='NO' AND character_maximum_length=2)
        OR (column_name='verified_at' AND data_type='datetime' AND is_nullable='NO' AND datetime_precision=6)
        OR (column_name='valid_until' AND data_type='datetime' AND is_nullable='NO' AND datetime_precision=6)
        OR (column_name='version' AND data_type='bigint' AND is_nullable='NO')))<>12
    OR (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music_playback_sources')<>12
    OR (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='music_playback_sources' AND constraint_name IN ('PRIMARY','fk_music_playback_source_music','chk_music_source_kind','chk_music_source_status','chk_music_source_duration','chk_music_source_period','chk_music_source_version'))<>7
    OR (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND table_name='music_playback_sources' AND constraint_name='PRIMARY' AND column_name='music_id' AND ordinal_position=1)<>1
    OR (SELECT COUNT(*) FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND table_name='music_playback_sources' AND constraint_name='fk_music_playback_source_music' AND column_name='music_id' AND referenced_table_schema=DATABASE() AND referenced_table_name='music' AND referenced_column_name='id')<>1
    OR (SELECT COUNT(*) FROM information_schema.check_constraints c JOIN information_schema.table_constraints t ON t.constraint_schema=c.constraint_schema AND t.constraint_name=c.constraint_name WHERE t.constraint_schema=DATABASE() AND t.table_name='music_playback_sources' AND t.enforced='YES' AND ((c.constraint_name='chk_music_source_kind' AND LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(c.check_clause,CHAR(92),''),'`',''),' ',''),'(',''),')',''),'_utf8mb4',''))='channel_kindin''artist'',''distributor'',''topic''') OR (c.constraint_name='chk_music_source_status' AND LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(c.check_clause,CHAR(92),''),'`',''),' ',''),'(',''),')',''),'_utf8mb4',''))='statusin''verified'',''unavailable''') OR (c.constraint_name='chk_music_source_duration' AND LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(c.check_clause,CHAR(92),''),'`',''),' ',''),'(',''),')',''),'_utf8mb4',''))='duration_millis>0') OR (c.constraint_name='chk_music_source_period' AND LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(c.check_clause,CHAR(92),''),'`',''),' ',''),'(',''),')',''),'_utf8mb4',''))='valid_until>verified_at') OR (c.constraint_name='chk_music_source_version' AND LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(c.check_clause,CHAR(92),''),'`',''),' ',''),'(',''),')',''),'_utf8mb4',''))='version>0')))<>5)), 'SELECT 1', 'SELECT music_catalog_compatibility_requires_expected_schema FROM information_schema.columns');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music' AND column_name='title_us'), 'SELECT 1', 'ALTER TABLE music ADD COLUMN title_us VARCHAR(255) NULL');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music' AND column_name='artist_name_us'), 'SELECT 1', 'ALTER TABLE music ADD COLUMN artist_name_us VARCHAR(1000) NULL');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music' AND column_name='title_ko'), 'SELECT 1', 'ALTER TABLE music ADD COLUMN title_ko VARCHAR(255) NULL');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music' AND column_name='artist_name_ko'), 'SELECT 1', 'ALTER TABLE music ADD COLUMN artist_name_ko VARCHAR(1000) NULL');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music' AND column_name='release_epoch_second'), 'SELECT 1', 'ALTER TABLE music ADD COLUMN release_epoch_second BIGINT NULL');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='music_playback_sources'), 'SELECT 1', 'CREATE TABLE music_playback_sources (     music_id BIGINT NOT NULL PRIMARY KEY,     youtube_video_id VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,     channel_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,     channel_kind VARCHAR(20) NOT NULL,     review_reference VARCHAR(1000) NOT NULL,     reviewed_by VARCHAR(100) NOT NULL,     status VARCHAR(20) NOT NULL,     duration_millis BIGINT NOT NULL,     playback_country CHAR(2) NOT NULL,     verified_at DATETIME(6) NOT NULL,     valid_until DATETIME(6) NOT NULL,     version BIGINT NOT NULL,     CONSTRAINT fk_music_playback_source_music FOREIGN KEY (music_id) REFERENCES music(id),     CONSTRAINT chk_music_source_kind CHECK (channel_kind IN (''ARTIST'',''DISTRIBUTOR'',''TOPIC'')),     CONSTRAINT chk_music_source_status CHECK (status IN (''VERIFIED'',''UNAVAILABLE'')),     CONSTRAINT chk_music_source_duration CHECK (duration_millis > 0),     CONSTRAINT chk_music_source_period CHECK (valid_until > verified_at),     CONSTRAINT chk_music_source_version CHECK (version > 0) )');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;
