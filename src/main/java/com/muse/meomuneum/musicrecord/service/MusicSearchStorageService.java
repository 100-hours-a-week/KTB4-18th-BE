package com.muse.meomuneum.musicrecord.service;

import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import com.muse.meomuneum.music.domain.MusicMetadataPolicy;
import com.muse.meomuneum.music.exception.MusicMetadataException;
import com.muse.meomuneum.musicrecord.dto.MusicRecordDtos.MusicItem;
import com.muse.meomuneum.musicrecord.exception.MusicRecordException;

@Service
public class MusicSearchStorageService {
    private static final Logger log = LoggerFactory.getLogger(MusicSearchStorageService.class);
    private static final int MAX_PROVIDER_FIELD_LENGTH = 1000;
    private final MusicSearchIngestJobService jobs;

    public MusicSearchStorageService(MusicSearchIngestJobService jobs) {
        this.jobs = jobs;
    }

    public void store(List<MusicItem> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        List<MusicItem> unique = new ArrayList<>(snapshot.size());
        Set<String> seen = new HashSet<>();
        for (MusicItem item : snapshot) {
            validate(item);
            if (seen.add(item.external_music_id())) {
                unique.add(item);
            }
        }
        try {
            jobs.admit(List.copyOf(unique));
        } catch (RuntimeException exception) {
            log.warn("event=music_search_admission_failed count={} exception={}", unique.size(),
                    exception.getClass().getSimpleName());
            throw new MusicRecordException("music_search_storage_unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                    "music search storage unavailable");
        }
    }

    public static void validate(MusicItem item) {
        if (item == null || !"ITUNES".equals(item.provider()) || item.external_music_id() == null
                || item.external_music_id().length() > 64 || !item.external_music_id().matches("[0-9]+")) {
            throw new MusicMetadataException("music_identity_invalid");
        }
        MusicMetadataPolicy.validate(item.title(), item.artist_name());
        validateOptionalField(item.album_cover_url(), "album_cover_url");
        validateOptionalField(item.preview_url(), "preview_url");
        validateLength(item.youtube_video_id(), "youtube_video_id", 32);
    }

    private static void validateOptionalField(String value, String name) {
        validateLength(value, name, MAX_PROVIDER_FIELD_LENGTH);
    }

    private static void validateLength(String value, String field, int maximum) {
        if (value != null && value.codePointCount(0, value.length()) > maximum) {
            throw new MusicMetadataException(field + "_too_long");
        }
    }

    public static boolean isRetryable(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean transientFailure = false;
        boolean connectionFailure = false;
        boolean transientSql = false;
        boolean sqlConnectionFailure = false;
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof MusicMetadataException || cause instanceof DataIntegrityViolationException
                    || cause instanceof BadSqlGrammarException || cause instanceof InvalidDataAccessApiUsageException
                    || cause instanceof PermissionDeniedDataAccessException
                    || cause instanceof SQLInvalidAuthorizationSpecException || cause instanceof Error) {
                return false;
            }
            transientFailure |= cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException;
            connectionFailure |= cause instanceof CannotCreateTransactionException
                    || cause instanceof DataAccessResourceFailureException;
            transientSql |= cause instanceof SQLTransientException || cause instanceof SQLRecoverableException;
            if (cause instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                sqlConnectionFailure |= sqlState != null && sqlState.startsWith("08");
            }
        }
        return transientFailure || (connectionFailure && transientSql) || sqlConnectionFailure;
    }
}
