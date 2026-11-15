SET @music_alignment_invalid = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='music_search_ingest_jobs' AND (
        (column_name='title_us' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>255)) OR
        (column_name='artist_name_us' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>1000)) OR
        (column_name='title_ko' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>255)) OR
        (column_name='artist_name_ko' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>1000)) OR
        (column_name='release_epoch_second' AND (data_type<>'bigint' OR is_nullable<>'YES'))
    )
);
SET @music_alignment_sql = IF(@music_alignment_invalid=0, 'SELECT 1',
    'SELECT music_search_alignment_requires_expected_nullable_column_types FROM information_schema.columns');
PREPARE music_alignment_statement FROM @music_alignment_sql;
EXECUTE music_alignment_statement;
DEALLOCATE PREPARE music_alignment_statement;

SET @music_alignment_sql = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='music_search_ingest_jobs' AND column_name='title_us'
), 'SELECT 1', 'ALTER TABLE music_search_ingest_jobs ADD COLUMN title_us VARCHAR(255) NULL');
PREPARE music_alignment_statement FROM @music_alignment_sql;
EXECUTE music_alignment_statement;
DEALLOCATE PREPARE music_alignment_statement;

SET @music_alignment_sql = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='music_search_ingest_jobs' AND column_name='artist_name_us'
), 'SELECT 1', 'ALTER TABLE music_search_ingest_jobs ADD COLUMN artist_name_us VARCHAR(1000) NULL');
PREPARE music_alignment_statement FROM @music_alignment_sql;
EXECUTE music_alignment_statement;
DEALLOCATE PREPARE music_alignment_statement;

SET @music_alignment_sql = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='music_search_ingest_jobs' AND column_name='title_ko'
), 'SELECT 1', 'ALTER TABLE music_search_ingest_jobs ADD COLUMN title_ko VARCHAR(255) NULL');
PREPARE music_alignment_statement FROM @music_alignment_sql;
EXECUTE music_alignment_statement;
DEALLOCATE PREPARE music_alignment_statement;

SET @music_alignment_sql = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='music_search_ingest_jobs' AND column_name='artist_name_ko'
), 'SELECT 1', 'ALTER TABLE music_search_ingest_jobs ADD COLUMN artist_name_ko VARCHAR(1000) NULL');
PREPARE music_alignment_statement FROM @music_alignment_sql;
EXECUTE music_alignment_statement;
DEALLOCATE PREPARE music_alignment_statement;

SET @music_alignment_sql = IF(EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='music_search_ingest_jobs' AND column_name='release_epoch_second'
), 'SELECT 1', 'ALTER TABLE music_search_ingest_jobs ADD COLUMN release_epoch_second BIGINT NULL');
PREPARE music_alignment_statement FROM @music_alignment_sql;
EXECUTE music_alignment_statement;
DEALLOCATE PREPARE music_alignment_statement;
