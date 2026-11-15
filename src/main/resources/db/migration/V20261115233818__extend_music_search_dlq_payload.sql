SET @compat_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music_search_ingest_dlq' AND ((column_name='title_us' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>255)) OR (column_name='artist_name_us' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>1000)) OR (column_name='title_ko' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>255)) OR (column_name='artist_name_ko' AND (data_type<>'varchar' OR is_nullable<>'YES' OR character_maximum_length<>1000)) OR (column_name='release_epoch_second' AND (data_type<>'bigint' OR is_nullable<>'YES'))))=0, 'SELECT 1', 'SELECT music_dlq_requires_expected_nullable_column_types FROM information_schema.columns');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music_search_ingest_dlq' AND column_name='title_us'), 'SELECT 1', 'ALTER TABLE music_search_ingest_dlq ADD COLUMN title_us VARCHAR(255) NULL');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music_search_ingest_dlq' AND column_name='artist_name_us'), 'SELECT 1', 'ALTER TABLE music_search_ingest_dlq ADD COLUMN artist_name_us VARCHAR(1000) NULL');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music_search_ingest_dlq' AND column_name='title_ko'), 'SELECT 1', 'ALTER TABLE music_search_ingest_dlq ADD COLUMN title_ko VARCHAR(255) NULL');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music_search_ingest_dlq' AND column_name='artist_name_ko'), 'SELECT 1', 'ALTER TABLE music_search_ingest_dlq ADD COLUMN artist_name_ko VARCHAR(1000) NULL');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;

SET @compat_sql = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='music_search_ingest_dlq' AND column_name='release_epoch_second'), 'SELECT 1', 'ALTER TABLE music_search_ingest_dlq ADD COLUMN release_epoch_second BIGINT NULL');
PREPARE compat_statement FROM @compat_sql;
EXECUTE compat_statement;
DEALLOCATE PREPARE compat_statement;
